package com.example.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

class SharedViewModel : ViewModel() {
    // Flag to indicate when log has been cleared
    private val _logCleared = MutableLiveData<Boolean>(false)
    val logCleared: LiveData<Boolean> = _logCleared

    // Set of scanned barcodes to maintain across fragments
    private val _scannedBarcodeSet = MutableLiveData<MutableSet<String>>(mutableSetOf())
    val scannedBarcodeSet: LiveData<MutableSet<String>> = _scannedBarcodeSet

    // Trigger log cleared event
    fun notifyLogCleared() {
        // Clear the barcode set
        _scannedBarcodeSet.value = mutableSetOf()
        // Trigger the event
        _logCleared.value = true
        // Reset the flag after it's been observed
        _logCleared.value = false
    }

    // Add a barcode to the scanned set
    fun addBarcodeToScannedSet(barcode: String) {
        val currentSet = _scannedBarcodeSet.value ?: mutableSetOf()
        currentSet.add(barcode)
        _scannedBarcodeSet.value = currentSet
    }

    // Check if a barcode exists in the set
    fun hasBarcodeBeenScanned(barcode: String): Boolean {
        return _scannedBarcodeSet.value?.contains(barcode) ?: false
    }

    // Initialize the barcode set with existing entries
    fun initializeScannedSet(barcodes: Set<String>) {
        _scannedBarcodeSet.value = barcodes.toMutableSet()
    }
}
