package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.fluxgram.MaterialStylePolicy;
import org.telegram.messenger.fluxgram.MaterialVisualStyle;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;

public final class FluxGramSettingsHeaderCell extends FrameLayout {
    private static final int TELEGRAM_HEIGHT_DP = 220;

    private final int preferredHeightDp;

    public FluxGramSettingsHeaderCell(
            Context context,
            Theme.ResourcesProvider resourcesProvider,
            MaterialVisualStyle.Snapshot materialStyle
    ) {
        super(context);

        MaterialVisualStyle.Shape iconShape = materialStyle.getShape(
                MaterialStylePolicy.Module.SURFACES,
                MaterialVisualStyle.ShapeRole.EXTRA_LARGE
        );
        MaterialVisualStyle.Typography titleTypography = materialStyle.getTypography(
                MaterialStylePolicy.Module.SURFACES,
                MaterialVisualStyle.TypographyRole.TITLE_LARGE
        );
        MaterialVisualStyle.Typography bodyTypography = materialStyle.getTypography(
                MaterialStylePolicy.Module.SURFACES,
                MaterialVisualStyle.TypographyRole.BODY_MEDIUM
        );
        Integer topSpacing = materialStyle.getSpacing(
                MaterialStylePolicy.Module.SURFACES,
                MaterialVisualStyle.SpacingRole.MEDIUM
        );
        Integer titleSpacing = materialStyle.getSpacing(
                MaterialStylePolicy.Module.SURFACES,
                MaterialVisualStyle.SpacingRole.SMALL
        );
        Integer bodySpacing = materialStyle.getSpacing(
                MaterialStylePolicy.Module.SURFACES,
                MaterialVisualStyle.SpacingRole.EXTRA_SMALL
        );
        boolean materialSurface = iconShape != null
                && titleTypography != null
                && bodyTypography != null
                && topSpacing != null
                && titleSpacing != null
                && bodySpacing != null;

        BackupImageView icon = new BackupImageView(context);
        icon.setRoundRadius(dp(materialSurface ? iconShape.getRadiusDp() : 24));
        icon.setImageDrawable(applicationIcon(context, materialSurface));
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        SimpleTextView name = text(
                context,
                "FluxGram",
                materialSurface ? titleTypography.getSizeSp() : 25,
                materialSurface,
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider)
        );
        name.setTypeface(materialSurface && titleTypography.getWeight() < 500
                ? AndroidUtilities.regular()
                : AndroidUtilities.bold());

        SimpleTextView version = text(
                context,
                version(context),
                materialSurface ? bodyTypography.getSizeSp() : 13,
                materialSurface,
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider)
        );
        version.setTypeface(materialSurface && bodyTypography.getWeight() >= 500
                ? AndroidUtilities.bold()
                : AndroidUtilities.regular());

        int iconTop = materialSurface ? topSpacing : 14;
        int titleTop = materialSurface ? iconTop + 96 + titleSpacing : 125;
        int titleHeight = materialSurface
                ? textHeightDp(context, name, titleTypography.getLineHeightSp())
                : 38;
        int bodyTop = materialSurface ? titleTop + titleHeight + bodySpacing : 165;
        int bodyHeight = materialSurface
                ? textHeightDp(context, version, bodyTypography.getLineHeightSp())
                : 24;
        preferredHeightDp = materialSurface
                ? Math.max(TELEGRAM_HEIGHT_DP, bodyTop + bodyHeight + topSpacing)
                : TELEGRAM_HEIGHT_DP;

        addView(icon, LayoutHelper.createFrame(
                96, 96, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, iconTop, 0, 0
        ));
        addView(name, LayoutHelper.createFrame(
                -1, titleHeight, Gravity.TOP, 24, titleTop, 24, 0
        ));
        addView(version, LayoutHelper.createFrame(
                -1, bodyHeight, Gravity.TOP, 24, bodyTop, 24, 0
        ));
    }

    public int getPreferredHeightDp() {
        return preferredHeightDp;
    }

    private SimpleTextView text(
            Context context,
            String value,
            int size,
            boolean scaled,
            int color
    ) {
        SimpleTextView view = new SimpleTextView(context);
        view.setText(value);
        if (scaled) {
            view.setTextSizePx(Math.round(TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    size,
                    context.getResources().getDisplayMetrics()
            )));
        } else {
            view.setTextSize(size);
        }
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);

        return view;
    }

    private int textHeightDp(Context context, SimpleTextView view, int lineHeightSp) {
        float rolePixels = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                lineHeightSp,
                context.getResources().getDisplayMetrics()
        );
        float fontPixels = view.getTextPaint().getFontMetricsInt(null);

        return (int) Math.ceil(
                Math.max(rolePixels, fontPixels)
                        / context.getResources().getDisplayMetrics().density
        );
    }

    private Drawable applicationIcon(Context context, boolean materialSurface) {
        Drawable icon = context.getApplicationInfo().loadIcon(context.getPackageManager());
        if (!materialSurface
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !(icon instanceof AdaptiveIconDrawable)) {
            return icon;
        }

        AdaptiveIconDrawable adaptiveIcon = (AdaptiveIconDrawable) icon;
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                adaptiveIcon.getBackground(),
                adaptiveIcon.getForeground()
        });
        int size = dp(96);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        layers.setBounds(0, 0, size, size);
        layers.draw(new Canvas(bitmap));

        return new BitmapDrawable(context.getResources(), bitmap);
    }

    private String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);

            return info.versionName + " (" + info.versionCode + ")";
        } catch (PackageManager.NameNotFoundException error) {
            return BuildVars.BUILD_VERSION_STRING;
        }
    }
}
