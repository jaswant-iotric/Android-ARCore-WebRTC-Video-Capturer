# ARCore-Webrtc-Video-Capturer (Android)
This code can be used to capture ARCore frames and stream them via WebRTC. 


If you have a working WebRTC Android app and you want to stream AR Core video to the WebRTC instead of plane camera feed you can directly use this file. 
I've optimised this to an extent, yet all the relevant PRs are welcome. 


Use this like a normal Video Capturer. 


   videoCapturer = new ArScreenCapturer(arSceneView, 15);
   
   videoSource = peerConnectionFactory.createVideoSource(videoCapturer.isScreencast());
   
   videoCapturer.initialize(surfaceTextureHelper, context, videoSource.getCapturerObserver());
