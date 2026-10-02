package com.lixingchi.pixellauncherfolder;

/** Pixel Launcher's logical grid spans can be finer than the visible app columns. */
final class GridSizing {
    static int appSpan(int spanCount, int appsPerRow) {
        if (appsPerRow <= 0 || spanCount < appsPerRow || spanCount % appsPerRow != 0)
            throw new IllegalArgumentException("Invalid launcher grid: " + spanCount + "/" + appsPerRow);
        return spanCount / appsPerRow;
    }
}
