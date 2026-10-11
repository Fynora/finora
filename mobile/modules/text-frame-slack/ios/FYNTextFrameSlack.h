#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Stops React Native dropping the last line of a paragraph whose frame came out a pixel short.
///
/// React Native measures a paragraph, rounds the height up to a whole pixel, and hands it to Yoga.
/// Yoga then rounds the paragraph's top and bottom edges to the pixel grid separately. Far enough
/// down a long screen the edges are sums of 32-bit floats that sit a hair off the grid, and the two
/// edges can round in opposite directions: the frame ends up one pixel shorter than the height that
/// was measured. On iOS the paragraph is then laid out again, at draw time, in a text container
/// exactly the size of that frame. A line that does not fit entirely is not drawn at all, and the
/// line before it takes the rest of the text, unwrapped and cut off at the right edge.
///
/// React Native's core is linked prebuilt, so neither Yoga's rounding nor the drawing code can be
/// patched from source. Instead this gives the text container one extra point of height, only while
/// a React Native paragraph is drawing. Lines keep their positions; the line that was a pixel short
/// of fitting is drawn. Measuring is untouched, so no layout moves.
@interface FYNTextFrameSlack : NSObject

/// Installs the hooks once. Returns NO, and changes nothing, when React Native's paragraph view is
/// not shaped the way this was written against (React Native 0.86).
+ (BOOL)install;

/// Whether `install` took effect.
@property (class, nonatomic, readonly) BOOL installed;

@end

NS_ASSUME_NONNULL_END
