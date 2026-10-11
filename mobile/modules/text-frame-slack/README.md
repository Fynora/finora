# text-frame-slack

iOS only. Native code with no JavaScript surface. It stops React Native dropping the last line of
a paragraph whose frame came out a pixel short.

It takes effect in a **new native build** (dev client, TestFlight, App Store). An over-the-air
JavaScript update cannot deliver it.

## The defect

Seen first on the Home screen at the iOS text size `accessibility-large`: a five-line paragraph
drew four lines, the fourth unwrapped and cut off at the right edge, and the last word was gone.

What happens, in order:

1. React Native measures the paragraph and rounds the height **up** to a whole pixel
   (`RCTTextLayoutManager.mm`, `_measureTextStorage`). On a 3x screen that is a multiple of a third
   of a point, which a 32-bit float cannot hold exactly.
2. Yoga rounds every node to the pixel grid (`yoga/algorithm/PixelGrid.cpp`,
   `roundLayoutResultsToPixelGrid`). For a text node it rounds the top edge and the bottom edge
   separately, from their absolute positions. A value within 0.0001 of a whole pixel snaps to it;
   anything else is rounded down.
3. A long way down a tall screen an absolute position is a sum of floats and sits a hair off the
   grid. The top edge can land just inside the 0.0001 tolerance and snap **up** while the bottom
   edge, a few hundred-thousandths further off, lands just outside it and is rounded **down**. The
   frame is then one pixel shorter than the height that was measured.
4. iOS lays the paragraph out again at draw time in a text container exactly the size of the frame
   (`drawAttributedString:paragraphAttributes:frame:`). A line that does not fit entirely is not
   drawn. The container's line break mode is clipping, so the line before it takes the rest of the
   text, unwrapped.

Yoga also rounds the whole tree again after every layout pass (`CalculateLayout.cpp`, end of
`calculateLayout`), feeding heights it has already rounded back in. In a simulation of repeated
passes a frame lost a second pixel that way and never a third: the worst shortfall was 0.667 pt.

There is a second, smaller way to the same result. Yoga stores the height as the bottom edge minus
the top edge, both 32-bit floats. When the text needs exactly a whole number of pixels (a fixed
`lineHeight` at the default text size does: 3 lines of 19 pt is 57 pt) and the paragraph straddles
a power of two in absolute position (2048 pt, 4096 pt), the two edges are held at different
precision and the difference comes out as 56.9999. TextKit drops the last line for that as well.

Measured on React Native 0.86.3, iPhone 17 Pro simulator, iOS 26.5, values read from the running
app with a debugger:

| | |
|---|---|
| height the text needs (5 lines) | 182.155 pt |
| height React Native measured | 182.3333 pt (547 px) |
| top edge passed to Yoga's rounding | 1498.99997 pt = 4496.99991 px, snapped up to 4497 |
| bottom edge | 1681.33330 pt = 5043.99989 px, rounded down to 5043 |
| frame Yoga stored | 182.0000 pt (546 px) |

Feeding that top and height to Yoga's own `roundValueToPixelGrid`, compiled on its own, gives
182.0 as well.

It is not specific to a fixed `lineHeight`, to large text or to one screen:

- The Home screen with real data, `accessibility-large`: 1 paragraph of 172 lost its last line.
  None did at three other text sizes, or on four other screens.
- A stress screen of 3,000 paragraphs: 5 lost their last line at `accessibility-large`, 2 at the
  **default** text size (both at y = 2010 pt, straddling 2048), none at
  `extra-extra-extra-large`.

So it is rare for any one paragraph, it depends on where the paragraph lands, and it is the same on
every launch for the same content. A simulation of Yoga's rounding at 2x found no case (halves are
exact in a float, thirds are not); that was not checked on a 2x device.

## The fix

React Native's core is linked prebuilt in this app (`React-Core-prebuilt`), so a `patch-package`
patch to Yoga or to the text drawing code would never be compiled in.

`FYNTextFrameSlack` instead replaces two Objective-C methods at launch:

- `-[RCTParagraphTextView drawRect:]` — counts itself in and out on the current thread.
- `-[NSLayoutManager addTextContainer:]` — while a paragraph is drawing, and only then, adds one
  point to the container's height.

Lines are laid out from the top, so every line keeps its position; the one that was a pixel short
of fitting is now drawn. Measuring is untouched (it uses a container of unbounded height, which is
skipped), so no frame moves and nothing reflows. `numberOfLines` still limits the line count.

One point covers the worst shortfall found (one pixel on a device, two in the simulation), and a
frame the app sized on purpose is short by a line or more, not by a point.

Checked by drawing every paragraph on a screen twice in the same process, once through React
Native's original `drawRect:` and once through the hook, and comparing lines, glyphs and font size
drawn: on Home (four text sizes), Insights, More and Accounts the only rows that changed were the
paragraphs that had lost a line; on the stress screen all 7 lost lines came back and every other
paragraph was identical (3,000 per run). Auto-shrunk amounts (`adjustsFontSizeToFit`) drew at the
same font size either way.

The hooks check what they are replacing first. If `RCTParagraphTextView` or its `drawRect:` is
missing or shaped differently, nothing is installed and one line is logged.

Known limit: only drawing is covered. A press on a nested `<Text onPress>` inside a last line that
was rescued this way is still resolved against the shorter layout.

## Checking it after a React Native upgrade

There is no unit test that can see pixels. `wiring.test.ts` only keeps the module linked and
started. To check the behaviour:

1. Build and run the dev client on a 3x simulator.
2. `xcrun simctl ui <udid> content_size accessibility-large`, then relaunch the app (a fast refresh
   keeps stale layout).
3. Look for `[TextFrameSlack] not installed` in the device log. It must not be there.
4. Walk Home at two text sizes and look for a paragraph whose last line is cut off mid-word.

When React Native stops laying text out a second time at draw time, or Yoga stops rounding a text
node's two edges separately, delete this module.
