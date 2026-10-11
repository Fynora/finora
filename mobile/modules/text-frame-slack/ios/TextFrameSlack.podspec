Pod::Spec.new do |s|
  s.name           = 'TextFrameSlack'
  s.version        = '1.0.0'
  s.summary        = 'Keeps the last line of a React Native paragraph when its frame is a pixel short.'
  s.description    = 'Local Fynora module. See ../README.md for the defect this works around and how to re-check it.'
  s.license        = 'UNLICENSED'
  s.author         = 'Fynora'
  s.homepage       = 'https://fynora.net'
  s.platforms      = { :ios => '16.4' }
  s.swift_version  = '5.9'
  s.source         = { :git => '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # Swift/Objective-C compatibility: the Swift subscriber calls the Objective-C hooks in this pod.
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES'
  }

  s.source_files = "**/*.{h,m,swift}"
end
