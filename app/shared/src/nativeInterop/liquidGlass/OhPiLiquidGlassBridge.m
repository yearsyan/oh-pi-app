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
