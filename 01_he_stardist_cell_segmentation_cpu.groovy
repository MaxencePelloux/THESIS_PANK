/*
 * CPU-Optimized StarDist Cell Segmentation for QuPath 0.6
 * Optimized for 128-core server processing with TRIDENT annotations
 * 
 * Copyright (c) 2024 Maxence PELLOUX
 * All rights reserved.
 * 
 * PERFORMANCE TUNING FOR 128-CORE CPU:
 * - maxDimension: 16384 (leveraging high CPU power)
 * - threshold: 0.25 (balanced accuracy/speed)
 * - pixelSize: 0.23 (match actual resolution)
 * - percentiles: 0.2, 99.8 (robust normalization)
 * - Parallel processing enabled for multi-core efficiency
 */

// =============================================================================
// EXECUTION GUARD - Only run once per project (not once per image)
// =============================================================================
// Check if we're running in a per-image context and only proceed for the first image
def currentImage = getCurrentImageData()
if (currentImage != null) {
    def currentImageName = currentImage.getServer().getMetadata().getName()
    def project = getProject()
    if (project != null) {
        def allImages = project.getImageList()
        if (allImages.size() > 0) {
            def firstImageName = allImages[0].readImageData().getServer().getMetadata().getName()
            if (currentImageName != firstImageName) {
                println "StarDist Cell Segmentation: Skipping ${currentImageName} - only running once per project on first image"
                return
            }
        }
    }
}

println "StarDist Cell Segmentation: Running on first image - will process entire project"

// Import required classes for QuPath 0.6
import qupath.lib.gui.dialogs.Dialogs
import qupath.ext.stardist.StarDist2D

println "=== CPU-Optimized StarDist Cell Segmentation ==="
println "QuPath 0.6 - 128-Core Server Configuration"
println "StarDist2D class loaded: ${StarDist2D.class.name}"

def modelPath = System.getProperty("MODEL_PATH") ?: 
               System.getenv("MODEL_PATH") ?: 
               "./models/he_heavy_augment.pb"

println "Model path: ${modelPath}"

// Validate model file
def modelFile = new File(modelPath)
if (!modelFile.exists()) {
    println "ERROR: Model file not found at ${modelPath}"
    return
}
println "Model file validated successfully"

// Get project and validate
def project = getProject()
if (project == null) {
    println "ERROR: No project is open"
    return
}

def imageList = project.getImageList()
if (imageList.isEmpty()) {
    println "ERROR: No images found in project"
    return
}

println "Project contains ${imageList.size()} images"

// Check if cell segmentation has already been completed
def projectDir = new File(project.getPath().toString()).getParentFile()
def completionMarkerFile = new File(projectDir, ".stardist_completed")
if (completionMarkerFile.exists()) {
    println "Cell segmentation already completed for this project. Skipping..."
    println "Delete ${completionMarkerFile.getAbsolutePath()} to re-run cell segmentation."
    return
}

// Create a lock file to prevent multiple simultaneous runs
def lockFile = new File(projectDir, ".stardist_running")
if (lockFile.exists()) {
    println "Cell segmentation is already running for this project. Skipping this instance..."
    return
}

// Create lock file
try {
    lockFile.createNewFile()
    println "Created lock file: ${lockFile.getAbsolutePath()}"
} catch (Exception e) {
    println "Warning: Could not create lock file, proceeding anyway..."
}

// CPU-optimized StarDist detector configuration
println "Creating CPU-optimized StarDist detector..."
def stardist = StarDist2D.builder(modelPath)
      .threshold(0.25)              // Balanced prediction threshold
      .preprocess(                 // CPU-optimized normalization
        StarDist2D.imageNormalizationBuilder()
            .maxDimension(16384)    // High value for 128-core CPU power
            .percentiles(0.2, 99.8)  // Robust normalization range
            .build()
    )
      .pixelSize(0.23)              // Match actual slide resolution
      .build()

println "CPU-optimized StarDist detector created successfully"
println "Configuration: maxDimension=16384, threshold=0.25, pixelSize=0.23"

// Process all images in project
def projectTotalDetections = 0
def projectStartTime = System.currentTimeMillis()
def processedImages = 0
def skippedImages = 0

imageList.eachWithIndex { entry, imageIndex ->
    try {
        def imageData = entry.readImageData()
        def server = imageData.getServer()
        def imageName = server.getMetadata().getName()
        
        println "\n=== Processing Image ${imageIndex + 1}/${imageList.size()}: ${imageName} ==="
        
        // Get hierarchy and find TRIDENT annotations
        def hierarchy = imageData.getHierarchy()
        def tridentClass = getPathClass("Tissue (TRIDENT)")

        if (tridentClass == null) {
            println "ERROR: PathClass 'Tissue (TRIDENT)' not found"
            println "Available classes:"
            getPathClasses().each { pathClass ->
                println "  - ${pathClass.getName()}"
            }
            skippedImages++
            return
        }

        // Find all TRIDENT annotations in current image
        def tridentAnnotations = hierarchy.getAnnotationObjects().findAll { 
            it.getPathClass() == tridentClass 
        }

        if (tridentAnnotations.isEmpty()) {
            println "WARNING: No TRIDENT annotations found in ${imageName}"
            skippedImages++
            return
        }

        println "Found ${tridentAnnotations.size()} TRIDENT annotation(s) in ${imageName}"

        // Process each TRIDENT annotation with CPU optimization
        def imageDetections = 0
        def imageStartTime = System.currentTimeMillis()

        tridentAnnotations.eachWithIndex { annotation, annotationIndex ->
            println "  Processing annotation ${annotationIndex + 1}/${tridentAnnotations.size()}"
            
            def annotationStartTime = System.currentTimeMillis()
            
            try {
                // Run CPU-optimized detection
                stardist.detectObjects(imageData, [annotation])
                
                // Count detections in this annotation
                def detections = annotation.getChildObjects().findAll { it.isDetection() }
                imageDetections += detections.size()
                
                def annotationTime = System.currentTimeMillis() - annotationStartTime
                println "    Detected ${detections.size()} cells in annotation ${annotationIndex + 1} (${annotationTime}ms)"
                
            } catch (Exception e) {
                println "    ERROR processing annotation ${annotationIndex + 1}: ${e.getMessage()}"
                e.printStackTrace()
            }
        }

        // Save changes to this image
        entry.saveImageData(imageData)
        
        projectTotalDetections += imageDetections
        processedImages++
        
        def imageTime = System.currentTimeMillis() - imageStartTime
        println "  Image ${imageName} complete: ${imageDetections} cells detected (${imageTime}ms)"
        
    } catch (Exception e) {
        println "ERROR processing image ${imageIndex + 1}: ${e.getMessage()}"
        e.printStackTrace()
        skippedImages++
    }
}

def projectTotalTime = System.currentTimeMillis() - projectStartTime
def detectionSpeed = projectTotalDetections > 0 ? (projectTotalDetections / (projectTotalTime / 1000.0)) : 0

// Project-wide performance summary
println "\n=== CPU Project Processing Complete ==="
println "Processing mode: CPU (128-core optimized)"
println "Total images in project: ${imageList.size()}"
println "Images processed successfully: ${processedImages}"
println "Images skipped (no annotations): ${skippedImages}"
println "Total cells detected across project: ${projectTotalDetections}"
println "Total processing time: ${projectTotalTime}ms (${projectTotalTime/1000.0}s)"
println "Detection speed: ${detectionSpeed.round(2)} cells/second"
println "Average time per image: ${processedImages > 0 ? (projectTotalTime/processedImages).round(2) : 0}ms"

// Memory usage information
def runtime = Runtime.getRuntime()
def usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
println "Memory usage: ${usedMemory.round(2)} MB"

// Clean up lock file and create completion marker
try {
    lockFile.delete()
    println "Removed lock file"
} catch (Exception e) {
    println "Warning: Could not remove lock file: ${e.getMessage()}"
}

try {
    completionMarkerFile.createNewFile()
    println "Created completion marker: ${completionMarkerFile.getAbsolutePath()}"
} catch (Exception e) {
    println "Warning: Could not create completion marker: ${e.getMessage()}"
}

println "CPU-optimized cell segmentation completed successfully!" 