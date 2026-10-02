#!/usr/bin/env python3
"""Build a macOS app with WebKit, the network engine and its Java runtime."""
from pathlib import Path
import plistlib
import platform
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
subprocess.run([sys.executable, str(ROOT / 'build.py')], check=True)
APP = ROOT / 'target/DJ Link.app/Contents'
(APP / 'MacOS').mkdir(parents=True, exist_ok=True)
resources = APP / 'Resources'
resources.mkdir(exist_ok=True)
java_home = Path(subprocess.check_output(['/usr/libexec/java_home'], text=True).strip())
runtime = resources / 'runtime'
if not runtime.exists():
    subprocess.run([str(java_home / 'bin/jlink'), '--add-modules',
                    'java.base,java.desktop,java.logging,java.sql,java.naming,java.net.http,jdk.httpserver,jdk.unsupported',
                    '--strip-debug', '--no-header-files', '--no-man-pages', '--output', str(runtime)], check=True)
shutil.copyfile(ROOT / 'target/djlink-1.0.0.jar', resources / 'djlink.jar')
shutil.copyfile(ROOT / 'THIRD_PARTY.md', resources / 'THIRD_PARTY.md')
if (ROOT / 'LICENSE').exists(): shutil.copyfile(ROOT / 'LICENSE', resources / 'LICENSE')
subprocess.run(['xcrun', 'swiftc', '-swift-version', '6', '-default-isolation', 'MainActor',
                '-target', f'{platform.machine()}-apple-macosx13.0', '-module-cache-path', str(ROOT / '.tools/swift-cache'), '-O',
                str(ROOT / 'macos/DJLink.swift'), '-o', str(APP / 'MacOS/DJLink')], check=True)
with (APP / 'Info.plist').open('wb') as out:
    plistlib.dump({'CFBundleName':'DJ Link', 'CFBundleDisplayName':'DJ Link',
                  'CFBundleExecutable':'DJLink', 'CFBundleIdentifier':'local.djlink.monitor',
                  'CFBundleVersion':'1', 'CFBundleShortVersionString':'1.0.0',
                  'CFBundlePackageType':'APPL', 'LSMinimumSystemVersion':'13.0', 'NSHighResolutionCapable':True,
                  'NSLocalNetworkUsageDescription':'Détecter le XDJ-AZ et recevoir les états PRO DJ LINK sur le réseau local.',
                  'NSAppTransportSecurity':{'NSAllowsLocalNetworking':True}}, out)
print('Application prête :', APP.parent)
