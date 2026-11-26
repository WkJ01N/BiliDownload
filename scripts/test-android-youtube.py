"""Live smoke test on an authorized, unlocked Android device with the main APK installed."""
import argparse
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "io.github.bilidownload"
SAMPLE = "https://youtu.be/jNQXAC9IVRw"
TITLE = "Me at the zoo"

def adb(*args):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    program = str(Path(sdk, "platform-tools", "adb")) if sdk else "adb"
    return subprocess.check_output([program, *args], timeout=30).decode("utf-8", "replace")

def screen():
    state = adb("shell", "dumpsys", "window")
    focus = next((line for line in state.splitlines() if "mCurrentFocus=" in line), "")
    if PACKAGE + "/" not in focus:
        raise RuntimeError("The app is not in the foreground. Check the phone for a system prompt.")
    tree = adb("exec-out", "uiautomator", "dump", "/dev/tty")
    start, end = tree.find("<?xml"), tree.rfind("</hierarchy>")
    if start < 0 or end < 0:
        return []
    return [n.attrib for n in ET.fromstring(tree[start:end+12]).iter("node") if n.get("package") == PACKAGE]

def locate(label, timeout=35, scroll=False, click=False, control=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        nodes = screen()
        for node in nodes:
            if (node.get("text") == label or node.get("content-desc") == label) and (
                    not control or node.get("class") == control):
                if click:
                    x1,y1,x2,y2 = map(int, re.findall(r"\d+", node["bounds"]))
                    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
                return
        errors = [n.get("text","") for n in nodes if n.get("text","").startswith(("YouTube 响应", "组件下载失败", "无法连接 YouTube", "YouTube 暂时", "YouTube 视频或", "下载失败", "没有符合画质"))]
        if errors: raise RuntimeError(errors[0])
        if scroll and nodes:
            widths = [list(map(int, re.findall(r"\d+", n["bounds"]))) for n in nodes]
            width = max(b[2] for b in widths); height = max(b[3] for b in widths)
            adb("shell","input","swipe",str(width//2),str(height*2//3),str(width//2),str(height//3),"350")
        time.sleep(.4)
    raise RuntimeError("Control did not appear: " + label)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("case", choices=["missing","download"])
    case = parser.parse_args().case
    adb("shell","am","start","-n",PACKAGE+"/.MainActivity","-a","android.intent.action.SEND","-t","text/plain","--es","android.intent.extra.TEXT",SAMPLE)
    time.sleep(1.5)
    locate("解析链接",click=True)
    locate("YouTube / X 解析组件")
    if case == "missing":
        locate("取消", click=True)
        print("PASS in-app component confirmation and cancellation")
        return
    locate("下载", click=True, control="android.widget.Button")
    locate("下载选中内容", timeout=140, scroll=True, click=True)
    locate("下载完成", timeout=240)
    locate("已完成",click=True)
    locate(TITLE, scroll=True)
    locate("分享视频",click=True)
    activities = adb("shell","dumpsys","activity","activities")
    if not re.search(r"ChooserActivity|ResolverActivity|android.intent.action.CHOOSER", activities):
        raise RuntimeError("Android share sheet did not open")
    adb("shell","input","keyevent","BACK")
    print("PASS Android share intent, extension, completed MP4 and system share sheet")

if __name__ == "__main__":
    main()
