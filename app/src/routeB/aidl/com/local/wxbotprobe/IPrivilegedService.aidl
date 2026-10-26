package com.local.wxbotprobe;

interface IPrivilegedService {
    void destroy() = 16777114;
    void screenshot(String path) = 1;
    void tap(int x, int y) = 2;
    int uid() = 3;
    void imeEnable(String id) = 4;
    void imeSet(String id) = 5;
    void keyEvent(int keyCode) = 6;
    String foregroundPackage() = 7;
}
