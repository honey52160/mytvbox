package android.text;

/**
 * 桌面端最小 TextPaint 实现（仅作为 TextUtils.ellipsize 等方法的参数类型占位）。
 */
public class TextPaint {

    public int color = 0xFF000000;
    public float textSize = 14f;
    public int textAlign = 0;
    public int flags = 0;

    public TextPaint() {
    }

    public TextPaint(TextPaint paint) {
        if (paint == null) return;
        this.color = paint.color;
        this.textSize = paint.textSize;
        this.textAlign = paint.textAlign;
        this.flags = paint.flags;
    }

    public void set(TextPaint paint) {
        if (paint == null) return;
        this.color = paint.color;
        this.textSize = paint.textSize;
        this.textAlign = paint.textAlign;
        this.flags = paint.flags;
    }

    public void setColor(int color) {
        this.color = color;
    }

    public int getColor() {
        return color;
    }

    public void setTextSize(float textSize) {
        this.textSize = textSize;
    }

    public float getTextSize() {
        return textSize;
    }
}
