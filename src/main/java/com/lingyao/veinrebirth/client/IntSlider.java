package com.lingyao.veinrebirth.client;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 原版风格的整数滑块：按住拖动，标签实时显示当前数值。
 */
@OnlyIn(Dist.CLIENT)
public class IntSlider extends AbstractSliderButton {

    private final String label;
    private final int min;
    private final int max;
    private final IntConsumer onChange;
    private boolean ready;

    public IntSlider(int x, int y, int width, int height, String label, int min, int max, int value,
            IntConsumer onChange) {
        super(x, y, width, height, Component.literal(label), toFraction(min, max, value));
        this.label = label;
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        this.ready = true;
        this.updateMessage();
    }

    public int getIntValue() {
        return this.min + (int) Math.round(this.value * (double) (this.max - this.min));
    }

    /** 直接设置数值（不会触发回调，用于切换矿物时刷新显示）。 */
    public void setIntValue(int value) {
        this.value = toFraction(this.min, this.max, Mth.clamp(value, this.min, this.max));
        this.updateMessage();
    }

    @Override
    protected void updateMessage() {
        if (!this.ready) {
            return;
        }
        this.setMessage(Component.literal(this.label + "：" + this.getIntValue()));
    }

    @Override
    protected void applyValue() {
        if (!this.ready) {
            return;
        }
        this.onChange.accept(this.getIntValue());
    }

    private static double toFraction(int min, int max, int value) {
        if (max <= min) {
            return 0.0D;
        }
        return (double) (Mth.clamp(value, min, max) - min) / (double) (max - min);
    }
}
