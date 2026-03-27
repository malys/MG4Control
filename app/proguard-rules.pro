# Keep all reflection targets intact (SAIC SDK, android.car.Car, ServiceManager)
-keep class android.car.** { *; }
-keep class android.os.ServiceManager { *; }
-keep class com.saicmotor.** { *; }
-keep class com.mg4.control.model.** { *; }
-keep class com.mg4.control.hardware.MG4Hardware { *; }
