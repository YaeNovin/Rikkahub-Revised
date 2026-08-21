# MuPDF's bundled native library resolves these generated Java bindings by name.
# Keep the JNI surface stable while still allowing method-body optimizations.
-keep,allowoptimization class com.artifex.mupdf.fitz.** { *; }

# Apache POI scratchpad and its Log4j API expose optional desktop/OSGi integration
# points that are not exercised by the Android document readers.
-dontwarn java.awt.**
-dontwarn org.osgi.framework.**
-dontwarn aQute.bnd.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
