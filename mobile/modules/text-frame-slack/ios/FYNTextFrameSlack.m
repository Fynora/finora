#import "FYNTextFrameSlack.h"

#import <UIKit/UIKit.h>
#import <objc/runtime.h>

// Extra container height, in points. The worst shortfall found was one pixel on a device and two
// (0.667 pt at 3x) in a simulation of repeated rounding passes; see README.md.
static const CGFloat FYNSlack = 1.0;

// React Native measures text in a container of height CGFLOAT_MAX. Anything this tall is that pass.
static const CGFloat FYNUnboundedHeight = 1.0e7;

static BOOL fynInstalled = NO;

// Above zero only while -[RCTParagraphTextView drawRect:] is on this thread's stack.
static _Thread_local NSInteger fynParagraphDrawDepth = 0;

static void (*fynOriginalDrawRect)(id, SEL, CGRect);
static void (*fynOriginalAddTextContainer)(id, SEL, NSTextContainer *);

static void FYNParagraphDrawRect(id self, SEL _cmd, CGRect rect)
{
  fynParagraphDrawDepth += 1;
  @try {
    fynOriginalDrawRect(self, _cmd, rect);
  } @finally {
    fynParagraphDrawDepth -= 1;
  }
}

static void FYNAddTextContainer(NSLayoutManager *self, SEL _cmd, NSTextContainer *container)
{
  if (fynParagraphDrawDepth > 0) {
    CGSize size = container.size;
    if (size.height > 0 && size.height < FYNUnboundedHeight) {
      container.size = CGSizeMake(size.width, size.height + FYNSlack);
    }
  }
  fynOriginalAddTextContainer(self, _cmd, container);
}

// The method as implemented by `cls` itself. class_getInstanceMethod would also return an inherited
// one, and replacing that would change every subclass of the class it was inherited from.
static Method FYNOwnInstanceMethod(Class cls, SEL selector)
{
  unsigned int count = 0;
  Method *methods = class_copyMethodList(cls, &count);
  Method found = NULL;
  for (unsigned int i = 0; i < count; i++) {
    if (method_getName(methods[i]) == selector) {
      found = methods[i];
      break;
    }
  }
  free(methods);
  return found;
}

static BOOL FYNTakesOneArgument(Method method, const char *typePrefix)
{
  if (method_getNumberOfArguments(method) != 3) {
    return NO;
  }
  char *type = method_copyArgumentType(method, 2);
  BOOL matches = type != NULL && strncmp(type, typePrefix, strlen(typePrefix)) == 0;
  free(type);
  return matches;
}

@implementation FYNTextFrameSlack

+ (BOOL)install
{
  static dispatch_once_t once;
  dispatch_once(&once, ^{
    Class paragraphTextView = NSClassFromString(@"RCTParagraphTextView");
    Method drawRect = paragraphTextView ? FYNOwnInstanceMethod(paragraphTextView, @selector(drawRect:)) : NULL;
    Method addTextContainer = FYNOwnInstanceMethod([NSLayoutManager class], @selector(addTextContainer:));

    if (drawRect == NULL || addTextContainer == NULL || !FYNTakesOneArgument(drawRect, "{CGRect=") ||
        !FYNTakesOneArgument(addTextContainer, "@")) {
      NSLog(@"[TextFrameSlack] not installed: React Native's paragraph view has changed shape. "
            @"A paragraph can lose its last line again; see mobile/modules/text-frame-slack/README.md.");
      return;
    }

    fynOriginalDrawRect = (void (*)(id, SEL, CGRect))method_getImplementation(drawRect);
    fynOriginalAddTextContainer = (void (*)(id, SEL, NSTextContainer *))method_getImplementation(addTextContainer);
    method_setImplementation(addTextContainer, (IMP)FYNAddTextContainer);
    method_setImplementation(drawRect, (IMP)FYNParagraphDrawRect);
    fynInstalled = YES;
  });
  return fynInstalled;
}

+ (BOOL)installed
{
  return fynInstalled;
}

@end
