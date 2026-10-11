import fs from 'fs';
import path from 'path';

// This module has no JavaScript surface: it is native iOS code that runs at app launch. What it
// does on screen can only be checked on a device (see README.md). What can break silently, with a
// green build, is the wiring that gets it linked and started -- an iOS build without it compiles
// and runs, and the clipped last line simply comes back. These tests hold that wiring in place.
describe('text-frame-slack wiring', () => {
  const root = __dirname;
  const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8');

  it('is autolinked on iOS and started by an app delegate subscriber', () => {
    const config = JSON.parse(read('expo-module.config.json'));

    expect(config.platforms).toEqual(['apple']);
    expect(config.apple.appDelegateSubscribers).toEqual(['TextFrameSlackAppDelegateSubscriber']);
  });

  it('has the subscriber the config names, and that subscriber installs the hooks at launch', () => {
    const subscriber = read('ios/TextFrameSlackAppDelegateSubscriber.swift');

    expect(subscriber).toMatch(/class TextFrameSlackAppDelegateSubscriber\s*:\s*ExpoAppDelegateSubscriber/);
    expect(subscriber).toMatch(/didFinishLaunchingWithOptions/);
    expect(subscriber).toContain('FYNTextFrameSlack.install()');
  });

  it('compiles the Objective-C hooks into the same pod as the subscriber', () => {
    const podspec = read('ios/TextFrameSlack.podspec');

    expect(podspec).toMatch(/s\.source_files\s*=\s*["']\*\*\/\*\.\{h,m,swift\}["']/);
    expect(podspec).toMatch(/'DEFINES_MODULE'\s*=>\s*'YES'/);
    expect(fs.existsSync(path.join(root, 'ios/FYNTextFrameSlack.h'))).toBe(true);
    expect(fs.existsSync(path.join(root, 'ios/FYNTextFrameSlack.m'))).toBe(true);
  });

  it('only ever touches text containers while a React Native paragraph is drawing', () => {
    const hooks = read('ios/FYNTextFrameSlack.m');

    // The NSLayoutManager hook is process-wide; the paragraph-draw depth is what scopes it.
    expect(hooks).toMatch(/if \(fynParagraphDrawDepth > 0\)/);
    // React Native measures text in a container of unbounded height. That pass must stay untouched.
    expect(hooks).toMatch(/size\.height < FYNUnboundedHeight/);
  });
});
