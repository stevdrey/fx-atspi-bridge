#!/usr/bin/env python3
"""Keep only this collector's X11 fixture windows above other windows until exit.

No focus, JVM, display cadence or persistent desktop settings are changed.
X11/XWayland only. The collector and its children get a private process group.
"""
import ctypes as c
import ctypes.util
import os
import signal
import subprocess
import sys
import time

class Client(c.Structure):
    _fields_ = [('type', c.c_int), ('serial', c.c_ulong), ('send_event', c.c_int),
                ('display', c.c_void_p), ('window', c.c_ulong), ('message_type', c.c_ulong),
                ('format', c.c_int), ('data', c.c_long * 5)]

class Event(c.Union):
    _fields_ = [('client', Client), ('pad', c.c_long * 24)]

x = c.CDLL(ctypes.util.find_library('X11'))
x.XOpenDisplay.argtypes = [c.c_char_p]
x.XOpenDisplay.restype = c.c_void_p
x.XDefaultRootWindow.argtypes = [c.c_void_p]
x.XDefaultRootWindow.restype = c.c_ulong
x.XInternAtom.argtypes = [c.c_void_p, c.c_char_p, c.c_int]
x.XInternAtom.restype = c.c_ulong
x.XGetWindowProperty.argtypes = [c.c_void_p, c.c_ulong, c.c_ulong, c.c_long, c.c_long,
                                c.c_int, c.c_ulong, c.POINTER(c.c_ulong), c.POINTER(c.c_int),
                                c.POINTER(c.c_ulong), c.POINTER(c.c_ulong), c.POINTER(c.c_void_p)]
x.XFree.argtypes = [c.c_void_p]
x.XSendEvent.argtypes = [c.c_void_p, c.c_ulong, c.c_int, c.c_long, c.POINTER(Event)]
x.XFlush.argtypes = [c.c_void_p]
x.XCloseDisplay.argtypes = [c.c_void_p]
# A window can disappear between enumerating it and reading its properties.
# Ignore only BadWindow; keep other X11 errors fatal to the supervisor.
class XError(c.Structure):
    _fields_ = [('type', c.c_int), ('display', c.c_void_p), ('resource', c.c_ulong),
                ('serial', c.c_ulong), ('code', c.c_ubyte), ('request', c.c_ubyte), ('minor', c.c_ubyte)]

unexpected_error = False

@c.CFUNCTYPE(c.c_int, c.c_void_p, c.POINTER(XError))
def error_handler(display, event):
    global unexpected_error
    if event.contents.code != 3:
        unexpected_error = True
    return 0

x.XSetErrorHandler.argtypes = [type(error_handler)]
x.XSetErrorHandler(error_handler)
display = x.XOpenDisplay(None)
if not display:
    raise SystemExit('X11 display unavailable')
root = x.XDefaultRootWindow(display)
atoms = {name: x.XInternAtom(display, name.encode(), False)
         for name in ('_NET_CLIENT_LIST', '_NET_WM_PID', '_NET_WM_STATE', '_NET_WM_STATE_ABOVE')}

def numbers(window, name):
    actual, form, size, after, ptr = c.c_ulong(), c.c_int(), c.c_ulong(), c.c_ulong(), c.c_void_p()
    status = x.XGetWindowProperty(display, window, atoms[name], 0, 1024, False, 0,
                                 c.byref(actual), c.byref(form), c.byref(size), c.byref(after), c.byref(ptr))
    try:
        if status or form.value != 32 or not ptr.value:
            return []
        return list(c.cast(ptr, c.POINTER(c.c_ulong))[:size.value])
    finally:
        if ptr.value:
            x.XFree(ptr)

child = subprocess.Popen(sys.argv[1:], start_new_session=True)
seen = set()
try:
    while child.poll() is None:
        if unexpected_error:
            raise RuntimeError('Unexpected X11 supervisor error')
        for window in numbers(root, '_NET_CLIENT_LIST'):
            pids = numbers(window, '_NET_WM_PID')
            if not pids or (window, pids[0]) in seen:
                continue
            try:
                if os.getpgid(pids[0]) != child.pid:
                    continue
            except ProcessLookupError:
                continue
            event = Event()
            event.client = Client(33, 0, True, display, window, atoms['_NET_WM_STATE'],
                                  32, (c.c_long * 5)(1, atoms['_NET_WM_STATE_ABOVE'], 0, 1, 0))
            x.XSendEvent(display, root, False, (1 << 20) | (1 << 19), c.byref(event))
            x.XFlush(display)
            seen.add((window, pids[0]))
            print('Kept collector fixture window above', flush=True)
        time.sleep(1)
    raise SystemExit(child.returncode)
finally:
    try:
        if child.poll() is None:
            try:
                os.killpg(child.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                child.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(child.pid, signal.SIGKILL)
                child.wait(timeout=10)
    finally:
        x.XCloseDisplay(display)
