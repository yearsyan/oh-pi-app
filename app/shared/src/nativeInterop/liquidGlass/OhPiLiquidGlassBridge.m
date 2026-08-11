#import "OhPiLiquidGlassBridge.h"

static const CGFloat OhPiComposerCornerRadius = 26.0;
static const CGFloat OhPiScrollButtonCornerRadius = 19.0;
static const CGFloat OhPiScrollButtonSymbolPointSize = 15.0;

BOOL OhPiLiquidGlassIsAvailable(void) {
    if (@available(iOS 26.0, *)) {
        return YES;
    }
    return NO;
}

API_AVAILABLE(ios(26.0))
@interface OhPiComposerLiquidGlassView : UIView

- (instancetype)initWithContentView:(UIView *)contentView;
- (void)ohpi_updateDarkAppearance:(BOOL)isDark;

@end

API_AVAILABLE(ios(26.0))
@implementation OhPiComposerLiquidGlassView {
    UIVisualEffectView *_glassView;
    UIView *_composeContentView;
}

- (instancetype)initWithContentView:(UIView *)contentView {
    self = [super initWithFrame:CGRectZero];
    if (self == nil) {
        return nil;
    }

    UIGlassEffect *effect = [UIGlassEffect effectWithStyle:UIGlassEffectStyleRegular];
    effect.interactive = NO;
    _glassView = [[UIVisualEffectView alloc] initWithEffect:effect];
    _composeContentView = contentView;

    self.opaque = NO;
    self.backgroundColor = UIColor.clearColor;
    self.clipsToBounds = YES;

    UICornerConfiguration *corners =
        [UICornerConfiguration configurationWithRadius:
            [UICornerRadius fixedRadius:OhPiComposerCornerRadius]];
    self.cornerConfiguration = corners;
    _glassView.cornerConfiguration = corners;
    _glassView.clipsToBounds = YES;
    _glassView.userInteractionEnabled = YES;
    _composeContentView.opaque = NO;
    _composeContentView.backgroundColor = UIColor.clearColor;

    [self addSubview:_glassView];
    [_glassView.contentView addSubview:_composeContentView];
    return self;
}

- (void)ohpi_updateDarkAppearance:(BOOL)isDark {
    UIUserInterfaceStyle style = isDark ? UIUserInterfaceStyleDark : UIUserInterfaceStyleLight;
    if (self.overrideUserInterfaceStyle != style) {
        self.overrideUserInterfaceStyle = style;
    }
}

- (void)layoutSubviews {
    [super layoutSubviews];
    _glassView.frame = self.bounds;
    _composeContentView.frame = _glassView.contentView.bounds;
}

@end

UIView * _Nullable OhPiLiquidGlassCreateComposerView(UIView *contentView) {
    if (@available(iOS 26.0, *)) {
        return [[OhPiComposerLiquidGlassView alloc] initWithContentView:contentView];
    }
    return nil;
}

void OhPiLiquidGlassUpdateComposerAppearance(UIView *view, BOOL isDark) {
    if (@available(iOS 26.0, *)) {
        if ([view isKindOfClass:OhPiComposerLiquidGlassView.class]) {
            [(OhPiComposerLiquidGlassView *)view ohpi_updateDarkAppearance:isDark];
        }
    }
}

API_AVAILABLE(ios(26.0))
@interface OhPiScrollButtonLiquidGlassView : UIView

- (instancetype)initWithTarget:(id)target action:(SEL)action;
- (void)ohpi_updateDarkAppearance:(BOOL)isDark
                        tintColor:(UIColor *)tintColor
               accessibilityLabel:(NSString *)accessibilityLabel;
- (void)ohpi_setProgress:(CGFloat)progress;

@end

API_AVAILABLE(ios(26.0))
@implementation OhPiScrollButtonLiquidGlassView {
    UIVisualEffectView *_glassView;
    UIImageView *_iconView;
    id _actionTarget;
}

- (instancetype)initWithTarget:(id)target action:(SEL)action {
    self = [super initWithFrame:CGRectZero];
    if (self == nil) {
        return nil;
    }

    _actionTarget = target;

    UIGlassEffect *effect = [UIGlassEffect effectWithStyle:UIGlassEffectStyleRegular];
    effect.interactive = YES;
    _glassView = [[UIVisualEffectView alloc] initWithEffect:effect];

    UIImageSymbolConfiguration *configuration =
        [UIImageSymbolConfiguration configurationWithPointSize:OhPiScrollButtonSymbolPointSize
                                                        weight:UIImageSymbolWeightSemibold];
    UIImage *image = [UIImage systemImageNamed:@"chevron.down" withConfiguration:configuration];
    _iconView = [[UIImageView alloc] initWithImage:image];

    self.opaque = NO;
    self.backgroundColor = UIColor.clearColor;
    self.isAccessibilityElement = YES;
    self.accessibilityTraits = UIAccessibilityTraitButton;

    UICornerConfiguration *corners =
        [UICornerConfiguration configurationWithRadius:
            [UICornerRadius fixedRadius:OhPiScrollButtonCornerRadius]];
    self.cornerConfiguration = corners;
    _glassView.cornerConfiguration = corners;
    _glassView.clipsToBounds = YES;
    _iconView.contentMode = UIViewContentModeCenter;

    [self addSubview:_glassView];
    [_glassView.contentView addSubview:_iconView];
    [self addGestureRecognizer:[[UITapGestureRecognizer alloc] initWithTarget:_actionTarget
                                                                      action:action]];
    return self;
}

- (void)ohpi_updateDarkAppearance:(BOOL)isDark
                        tintColor:(UIColor *)tintColor
               accessibilityLabel:(NSString *)accessibilityLabel {
    UIUserInterfaceStyle style = isDark ? UIUserInterfaceStyleDark : UIUserInterfaceStyleLight;
    if (self.overrideUserInterfaceStyle != style) {
        self.overrideUserInterfaceStyle = style;
    }
    _iconView.tintColor = tintColor;
    if (![self.accessibilityLabel isEqualToString:accessibilityLabel]) {
        self.accessibilityLabel = accessibilityLabel;
    }
}

- (void)ohpi_setProgress:(CGFloat)progress {
    CGFloat clamped = MIN(MAX(progress, 0.0), 1.0);
    self.alpha = clamped;
    CGFloat scale = 0.85 + (0.15 * clamped);
    self.transform = CGAffineTransformMakeScale(scale, scale);
    BOOL shouldHide = clamped <= 0.01;
    if (self.hidden != shouldHide) {
        self.hidden = shouldHide;
    }
}

- (void)layoutSubviews {
    [super layoutSubviews];
    _glassView.frame = self.bounds;
    _iconView.frame = _glassView.contentView.bounds;
}

@end

UIView * _Nullable OhPiLiquidGlassCreateScrollButtonView(id target, SEL action) {
    if (@available(iOS 26.0, *)) {
        return [[OhPiScrollButtonLiquidGlassView alloc] initWithTarget:target action:action];
    }
    return nil;
}

void OhPiLiquidGlassUpdateScrollButton(
    UIView *view,
    BOOL isDark,
    UIColor *tintColor,
    NSString *accessibilityLabel
) {
    if (@available(iOS 26.0, *)) {
        if ([view isKindOfClass:OhPiScrollButtonLiquidGlassView.class]) {
            [(OhPiScrollButtonLiquidGlassView *)view ohpi_updateDarkAppearance:isDark
                                                                     tintColor:tintColor
                                                            accessibilityLabel:accessibilityLabel];
        }
    }
}

void OhPiLiquidGlassSetScrollButtonProgress(UIView *view, CGFloat progress) {
    if (@available(iOS 26.0, *)) {
        if ([view isKindOfClass:OhPiScrollButtonLiquidGlassView.class]) {
            [(OhPiScrollButtonLiquidGlassView *)view ohpi_setProgress:progress];
        }
    }
}

@interface OhPiToastWindow : UIWindow
@end

@implementation OhPiToastWindow

// Let touches continue to the app while this higher-level window is visible.
- (UIView * _Nullable)hitTest:(CGPoint)point withEvent:(UIEvent * _Nullable)event {
    (void)point;
    (void)event;
    return nil;
}

@end

static OhPiToastWindow * _Nullable OhPiToastOverlayWindow;
static UIView * _Nullable OhPiToastOverlayView;

static UIWindowScene * _Nullable OhPiActiveWindowScene(void) {
    UIWindowScene *fallback = nil;
    for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
        if (![scene isKindOfClass:UIWindowScene.class]) {
            continue;
        }
        UIWindowScene *windowScene = (UIWindowScene *)scene;
        if (windowScene.activationState == UISceneActivationStateForegroundActive) {
            return windowScene;
        }
        if (fallback == nil && windowScene.activationState == UISceneActivationStateForegroundInactive) {
            fallback = windowScene;
        }
    }
    return fallback;
}

static UIColor *OhPiToastAccentColor(int32_t kind) {
    switch (kind) {
        case 1:
            return UIColor.systemRedColor;
        case 2:
            return UIColor.systemGreenColor;
        default:
            return UIColor.systemBlueColor;
    }
}

static NSString *OhPiToastSymbolName(int32_t kind) {
    switch (kind) {
        case 1:
            return @"exclamationmark.circle.fill";
        case 2:
            return @"checkmark.circle.fill";
        default:
            return @"info.circle.fill";
    }
}

static UIVisualEffectView *OhPiCreateToastBackground(BOOL isDark, UIColor *accentColor) {
    UIVisualEffect *effect;
    if (@available(iOS 26.0, *)) {
        UIGlassEffect *glass = [UIGlassEffect effectWithStyle:UIGlassEffectStyleRegular];
        glass.interactive = NO;
        effect = glass;
    } else {
        UIBlurEffectStyle style =
            isDark ? UIBlurEffectStyleSystemMaterialDark : UIBlurEffectStyleSystemMaterialLight;
        effect = [UIBlurEffect effectWithStyle:style];
    }

    UIVisualEffectView *background = [[UIVisualEffectView alloc] initWithEffect:effect];
    background.translatesAutoresizingMaskIntoConstraints = NO;
    background.clipsToBounds = YES;
    background.layer.cornerRadius = 18.0;
    background.contentView.backgroundColor = [accentColor colorWithAlphaComponent:isDark ? 0.16 : 0.10];
    if (@available(iOS 26.0, *)) {
        UICornerConfiguration *corners =
            [UICornerConfiguration configurationWithRadius:[UICornerRadius fixedRadius:18.0]];
        background.cornerConfiguration = corners;
    }
    return background;
}

static void OhPiHideCurrentToastImmediately(void) {
    OhPiToastOverlayWindow.hidden = YES;
    OhPiToastOverlayWindow = nil;
    OhPiToastOverlayView = nil;
}

void OhPiNativeToastShow(NSString *message, int32_t kind, BOOL isDark) {
    if (![NSThread isMainThread]) {
        NSString *copiedMessage = [message copy];
        dispatch_async(dispatch_get_main_queue(), ^{
            OhPiNativeToastShow(copiedMessage, kind, isDark);
        });
        return;
    }
    if (message.length == 0) {
        OhPiNativeToastDismiss();
        return;
    }

    UIWindowScene *windowScene = OhPiActiveWindowScene();
    if (windowScene == nil) {
        return;
    }
    OhPiHideCurrentToastImmediately();

    UIColor *accentColor = OhPiToastAccentColor(kind);
    OhPiToastWindow *window = [[OhPiToastWindow alloc] initWithWindowScene:windowScene];
    window.frame = windowScene.coordinateSpace.bounds;
    window.windowLevel = UIWindowLevelAlert + 1.0;
    window.backgroundColor = UIColor.clearColor;
    window.userInteractionEnabled = NO;

    UIViewController *root = [[UIViewController alloc] init];
    root.overrideUserInterfaceStyle = isDark ? UIUserInterfaceStyleDark : UIUserInterfaceStyleLight;
    root.view.backgroundColor = UIColor.clearColor;
    root.view.userInteractionEnabled = NO;
    window.rootViewController = root;

    UIView *toastView = [[UIView alloc] initWithFrame:CGRectZero];
    toastView.translatesAutoresizingMaskIntoConstraints = NO;
    toastView.backgroundColor = UIColor.clearColor;
    toastView.layer.shadowColor = UIColor.blackColor.CGColor;
    toastView.layer.shadowOpacity = isDark ? 0.28f : 0.16f;
    toastView.layer.shadowRadius = 12.0;
    toastView.layer.shadowOffset = CGSizeMake(0.0, 5.0);
    toastView.isAccessibilityElement = YES;
    toastView.accessibilityLabel = message;

    UIVisualEffectView *background = OhPiCreateToastBackground(isDark, accentColor);
    [toastView addSubview:background];
    [NSLayoutConstraint activateConstraints:@[
        [background.leadingAnchor constraintEqualToAnchor:toastView.leadingAnchor],
        [background.trailingAnchor constraintEqualToAnchor:toastView.trailingAnchor],
        [background.topAnchor constraintEqualToAnchor:toastView.topAnchor],
        [background.bottomAnchor constraintEqualToAnchor:toastView.bottomAnchor],
    ]];

    UIImageSymbolConfiguration *symbolConfiguration =
        [UIImageSymbolConfiguration configurationWithPointSize:17.0
                                                        weight:UIImageSymbolWeightSemibold];
    UIImage *symbol =
        [UIImage systemImageNamed:OhPiToastSymbolName(kind) withConfiguration:symbolConfiguration];
    UIImageView *icon = [[UIImageView alloc] initWithImage:symbol];
    icon.translatesAutoresizingMaskIntoConstraints = NO;
    icon.tintColor = accentColor;
    icon.contentMode = UIViewContentModeCenter;

    UILabel *label = [[UILabel alloc] initWithFrame:CGRectZero];
    label.translatesAutoresizingMaskIntoConstraints = NO;
    label.text = message;
    label.textColor = UIColor.labelColor;
    label.font = [UIFont preferredFontForTextStyle:UIFontTextStyleSubheadline];
    label.adjustsFontForContentSizeCategory = YES;
    label.numberOfLines = 3;
    label.lineBreakMode = NSLineBreakByTruncatingTail;

    UIStackView *content = [[UIStackView alloc] initWithArrangedSubviews:@[icon, label]];
    content.translatesAutoresizingMaskIntoConstraints = NO;
    content.axis = UILayoutConstraintAxisHorizontal;
    content.alignment = UIStackViewAlignmentCenter;
    content.spacing = 10.0;
    [background.contentView addSubview:content];
    [NSLayoutConstraint activateConstraints:@[
        [icon.widthAnchor constraintEqualToConstant:20.0],
        [icon.heightAnchor constraintEqualToConstant:20.0],
        [content.leadingAnchor constraintEqualToAnchor:background.contentView.leadingAnchor constant:15.0],
        [content.trailingAnchor constraintEqualToAnchor:background.contentView.trailingAnchor constant:-16.0],
        [content.topAnchor constraintEqualToAnchor:background.contentView.topAnchor constant:11.0],
        [content.bottomAnchor constraintEqualToAnchor:background.contentView.bottomAnchor constant:-11.0],
    ]];

    [root.view addSubview:toastView];
    [NSLayoutConstraint activateConstraints:@[
        [toastView.centerXAnchor constraintEqualToAnchor:root.view.centerXAnchor],
        [toastView.leadingAnchor constraintGreaterThanOrEqualToAnchor:root.view.leadingAnchor constant:20.0],
        [toastView.trailingAnchor constraintLessThanOrEqualToAnchor:root.view.trailingAnchor constant:-20.0],
        [toastView.widthAnchor constraintLessThanOrEqualToConstant:520.0],
        [toastView.bottomAnchor constraintEqualToAnchor:root.view.safeAreaLayoutGuide.bottomAnchor constant:-24.0],
    ]];

    OhPiToastOverlayWindow = window;
    OhPiToastOverlayView = toastView;
    window.hidden = NO;
    [root.view layoutIfNeeded];

    toastView.alpha = 0.0;
    toastView.transform = CGAffineTransformMakeTranslation(0.0, 12.0);
    [UIView animateWithDuration:0.22
                          delay:0.0
                        options:UIViewAnimationOptionCurveEaseOut | UIViewAnimationOptionBeginFromCurrentState
                     animations:^{
                         toastView.alpha = 1.0;
                         toastView.transform = CGAffineTransformIdentity;
                     }
                     completion:nil];
    UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, message);
}

void OhPiNativeToastDismiss(void) {
    if (![NSThread isMainThread]) {
        dispatch_async(dispatch_get_main_queue(), ^{
            OhPiNativeToastDismiss();
        });
        return;
    }

    OhPiToastWindow *window = OhPiToastOverlayWindow;
    UIView *toastView = OhPiToastOverlayView;
    OhPiToastOverlayWindow = nil;
    OhPiToastOverlayView = nil;
    if (window == nil || toastView == nil) {
        return;
    }

    [UIView animateWithDuration:0.18
                          delay:0.0
                        options:UIViewAnimationOptionCurveEaseIn | UIViewAnimationOptionBeginFromCurrentState
                     animations:^{
                         toastView.alpha = 0.0;
                         toastView.transform = CGAffineTransformMakeTranslation(0.0, 8.0);
                     }
                     completion:^(BOOL finished) {
                         (void)finished;
                         window.hidden = YES;
                     }];
}
