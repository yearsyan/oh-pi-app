#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// Returns YES only when the iOS 26 Liquid Glass APIs are available at runtime.
FOUNDATION_EXPORT BOOL OhPiLiquidGlassIsAvailable(void);

/// Wraps Compose content in a non-interactive Liquid Glass surface on iOS 26+.
FOUNDATION_EXPORT UIView * _Nullable OhPiLiquidGlassCreateComposerView(UIView *contentView);

/// Applies the app's forced light/dark appearance to a composer glass surface.
FOUNDATION_EXPORT void OhPiLiquidGlassUpdateComposerAppearance(UIView *view, BOOL isDark);

/// Creates the interactive circular scroll-to-bottom button on iOS 26+.
FOUNDATION_EXPORT UIView * _Nullable OhPiLiquidGlassCreateScrollButtonView(id target, SEL action);

/// Updates theme-dependent scroll button properties.
FOUNDATION_EXPORT void OhPiLiquidGlassUpdateScrollButton(
    UIView *view,
    BOOL isDark,
    UIColor *tintColor,
    NSString *accessibilityLabel
);

/// Applies the Compose-owned fade and scale animation progress to the native button.
FOUNDATION_EXPORT void OhPiLiquidGlassSetScrollButtonProgress(UIView *view, CGFloat progress);

NS_ASSUME_NONNULL_END
