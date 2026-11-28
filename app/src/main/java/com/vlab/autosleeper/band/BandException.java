package com.vlab.autosleeper.band;

/**
 * BLE 操作失败。带一个机器可读的原因，界面/广播可以直接给用户看人话。
 */
public class BandException extends Exception {

    private static final long serialVersionUID = 1L;

    private final BandError error;

    public BandException(BandError error) {
        super(error.text());
        this.error = error;
    }

    public BandException(BandError error, String detail) {
        super(detail == null || detail.isEmpty() ? error.text() : error.text() + "：" + detail);
        this.error = error;
    }

    public BandError error() {
        return error;
    }
}
