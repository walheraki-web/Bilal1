# Keep the privileged capture helper started by name through app_process.
-keep class com.bilal.tv.capture.CaptureMain { *; }
# Vosk/JNA
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
