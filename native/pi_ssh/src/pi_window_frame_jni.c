#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <windows.h>
#include <windowsx.h>
#include <dwmapi.h>

static const wchar_t OHPI_FRAME_STATE_PROPERTY[] =
    L"io.github.yearsyan.ohpi.frame-state";

typedef struct ohpi_frame_state {
    WNDPROC previous_procedure;
} ohpi_frame_state;

static LONG ohpi_managed_frame_style(LONG style)
{
    return (style & ~WS_CAPTION) |
           WS_THICKFRAME |
           WS_SYSMENU |
           WS_MINIMIZEBOX |
           WS_MAXIMIZEBOX;
}

static ohpi_frame_state *ohpi_get_frame_state(HWND window)
{
    return (ohpi_frame_state *)GetPropW(window, OHPI_FRAME_STATE_PROPERTY);
}

static LRESULT ohpi_call_previous(ohpi_frame_state *state,
                                  HWND window,
                                  UINT message,
                                  WPARAM w_param,
                                  LPARAM l_param)
{
    if (state == NULL || state->previous_procedure == NULL) {
        return DefWindowProcW(window, message, w_param, l_param);
    }
    return CallWindowProcW(state->previous_procedure,
                           window,
                           message,
                           w_param,
                           l_param);
}

static LRESULT ohpi_resize_hit_test(HWND window, LPARAM l_param)
{
    RECT bounds;
    UINT dpi;
    int horizontal_border;
    int vertical_border;
    int x;
    int y;
    BOOL on_left;
    BOOL on_right;
    BOOL on_top;
    BOOL on_bottom;

    if (!GetWindowRect(window, &bounds)) {
        return HTNOWHERE;
    }

    x = GET_X_LPARAM(l_param);
    y = GET_Y_LPARAM(l_param);
    dpi = GetDpiForWindow(window);
    if (dpi == 0) {
        dpi = 96;
    }
    horizontal_border =
        GetSystemMetricsForDpi(SM_CXSIZEFRAME, dpi) +
        GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
    vertical_border =
        GetSystemMetricsForDpi(SM_CYSIZEFRAME, dpi) +
        GetSystemMetricsForDpi(SM_CXPADDEDBORDER, dpi);
    if (horizontal_border < 1) {
        horizontal_border = 1;
    }
    if (vertical_border < 1) {
        vertical_border = 1;
    }

    on_left = x < bounds.left + horizontal_border;
    on_right = x >= bounds.right - horizontal_border;
    on_top = y < bounds.top + vertical_border;
    on_bottom = y >= bounds.bottom - vertical_border;

    if (on_top && on_left) {
        return HTTOPLEFT;
    }
    if (on_top && on_right) {
        return HTTOPRIGHT;
    }
    if (on_bottom && on_left) {
        return HTBOTTOMLEFT;
    }
    if (on_bottom && on_right) {
        return HTBOTTOMRIGHT;
    }
    if (on_left) {
        return HTLEFT;
    }
    if (on_right) {
        return HTRIGHT;
    }
    if (on_top) {
        return HTTOP;
    }
    if (on_bottom) {
        return HTBOTTOM;
    }
    return HTNOWHERE;
}

static LRESULT CALLBACK ohpi_frame_window_procedure(HWND window,
                                                     UINT message,
                                                     WPARAM w_param,
                                                     LPARAM l_param)
{
    ohpi_frame_state *state = ohpi_get_frame_state(window);

    if (message == WM_STYLECHANGING &&
        (int)(intptr_t)w_param == GWL_STYLE &&
        l_param != 0) {
        STYLESTRUCT *styles = (STYLESTRUCT *)l_param;
        styles->styleNew = (DWORD)ohpi_managed_frame_style((LONG)styles->styleNew);
        return ohpi_call_previous(state, window, message, w_param, l_param);
    }

    if (message == WM_NCCALCSIZE && l_param != 0) {
        RECT proposed_bounds = *(RECT *)l_param;
        LRESULT result =
            ohpi_call_previous(state, window, message, w_param, l_param);
        if (!IsZoomed(window)) {
            *(RECT *)l_param = proposed_bounds;
            return 0;
        }
        return result;
    }

    if (message == WM_NCHITTEST) {
        LRESULT result =
            ohpi_call_previous(state, window, message, w_param, l_param);
        LRESULT resize_result;
        if (IsZoomed(window)) {
            return result;
        }
        resize_result = ohpi_resize_hit_test(window, l_param);
        if (resize_result != HTNOWHERE) {
            return resize_result;
        }
        return result == HTCAPTION ? HTCLIENT : result;
    }

    if (message == WM_NCDESTROY && state != NULL) {
        WNDPROC previous = state->previous_procedure;
        SetWindowLongPtrW(window, GWLP_WNDPROC, (LONG_PTR)previous);
        RemovePropW(window, OHPI_FRAME_STATE_PROPERTY);
        free(state);
        return previous == NULL
                   ? DefWindowProcW(window, message, w_param, l_param)
                   : CallWindowProcW(previous,
                                     window,
                                     message,
                                     w_param,
                                     l_param);
    }

    return ohpi_call_previous(state, window, message, w_param, l_param);
}

static BOOL ohpi_install_frame_window_procedure(HWND window)
{
    ohpi_frame_state *state;
    LONG_PTR previous;

    if (ohpi_get_frame_state(window) != NULL) {
        return TRUE;
    }

    state = (ohpi_frame_state *)calloc(1, sizeof(*state));
    if (state == NULL) {
        return FALSE;
    }
    if (!SetPropW(window, OHPI_FRAME_STATE_PROPERTY, (HANDLE)state)) {
        free(state);
        return FALSE;
    }

    SetLastError(ERROR_SUCCESS);
    previous = SetWindowLongPtrW(window,
                                 GWLP_WNDPROC,
                                 (LONG_PTR)ohpi_frame_window_procedure);
    if (previous == 0 && GetLastError() != ERROR_SUCCESS) {
        RemovePropW(window, OHPI_FRAME_STATE_PROPERTY);
        free(state);
        return FALSE;
    }
    state->previous_procedure = (WNDPROC)previous;
    return TRUE;
}

static BOOL ohpi_apply_windows_window_frame(HWND window)
{
    LONG current_style;
    LONG previous_style;
    int rendering_policy = DWMNCRP_ENABLED;
    int corner_preference = DWMWCP_ROUND;
    MARGINS margins = {1, 1, 1, 1};

    if (window == NULL || !ohpi_install_frame_window_procedure(window)) {
        return FALSE;
    }

    current_style = GetWindowLongW(window, GWL_STYLE);
    SetLastError(ERROR_SUCCESS);
    previous_style =
        SetWindowLongW(window,
                       GWL_STYLE,
                       ohpi_managed_frame_style(current_style));
    if (previous_style == 0 && GetLastError() != ERROR_SUCCESS) {
        return FALSE;
    }
    if (!SetWindowPos(window,
                      NULL,
                      0,
                      0,
                      0,
                      0,
                      SWP_NOSIZE |
                          SWP_NOMOVE |
                          SWP_NOZORDER |
                          SWP_NOACTIVATE |
                          SWP_FRAMECHANGED)) {
        return FALSE;
    }

    DwmSetWindowAttribute(window,
                          DWMWA_NCRENDERING_POLICY,
                          &rendering_policy,
                          sizeof(rendering_policy));
    DwmSetWindowAttribute(window,
                          DWMWA_WINDOW_CORNER_PREFERENCE,
                          &corner_preference,
                          sizeof(corner_preference));
    return SUCCEEDED(DwmExtendFrameIntoClientArea(window, &margins));
}

JNIEXPORT jboolean JNICALL
Java_io_github_yearsyan_ohpi_ssh_NativeSshBridge_nativeApplyWindowsWindowFrame(
    JNIEnv *environment,
    jclass bridge_class,
    jlong window_handle)
{
    (void)environment;
    (void)bridge_class;
    return ohpi_apply_windows_window_frame((HWND)(intptr_t)window_handle)
               ? JNI_TRUE
               : JNI_FALSE;
}
