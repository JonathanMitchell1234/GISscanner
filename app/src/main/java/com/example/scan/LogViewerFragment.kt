package com.example.scan

import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.scan.databinding.FragmentLogViewerBinding
import com.opencsv.CSVReader
import java.io.File
import java.io.FileReader
import java.io.IOException

class LogViewerFragment : Fragment() {

    private var _binding: FragmentLogViewerBinding? = null
    private val binding get() = _binding!!

    private val logEntries = mutableListOf<LogEntry>()
    private lateinit var logAdapter: LogAdapter
    private val logFileName by lazy { getString(R.string.log_file_name) }

    // Use the shared view model
    private val sharedViewModel: SharedViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        loadLogData()

        binding.clearLogButton.setOnClickListener {
            showClearLogConfirmationDialog()
        }
    }

    private fun setupRecyclerView() {
        logAdapter = LogAdapter(logEntries)
        binding.logRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = logAdapter
        }
    }

    private fun loadLogData() {
        logEntries.clear()
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, logFileName)

        if (!file.exists()) {
            showToast(getString(R.string.log_file_not_found_toast))
            return
        }

        try {
            CSVReader(FileReader(file)).use { csvReader ->
                var line: Array<String>?
                csvReader.readNext() // Skip header row
                while (csvReader.readNext().also { line = it } != null) {
                    if (line!!.size >= 2) {
                        logEntries.add(LogEntry(barcode = line!![0], timestamp = line!![1]))
                    } else {
                        Log.w(TAG, "Skipping malformed CSV line: ${line.contentToString()}")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading CSV file: ${e.message}", e)
            showToast(getString(R.string.error_reading_log_file_toast))
        } catch (e: Exception) {
            Log.e(TAG, "An unexpected error occurred while reading CSV: ${e.message}", e)
            showToast(getString(R.string.error_unexpected_csv_read_toast))
        }

        if (logEntries.isEmpty() && file.exists()) {
            // File exists but might be empty (only header) or all lines were malformed
            showToast(getString(R.string.log_file_empty_toast))
        }

        logAdapter.notifyDataSetChanged()
    }

    private fun showClearLogConfirmationDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.confirm_clear_log_dialog_title))
            .setMessage(getString(R.string.confirm_clear_log_dialog_message))
            .setPositiveButton(getString(R.string.dialog_yes)) { _, _ ->
                clearLogFile()
            }
            .setNegativeButton(getString(R.string.dialog_no), null)
            .show()
    }

    private fun clearLogFile() {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, logFileName)
        var clearedSuccessfully = false
        if (file.exists()) {
            if (file.delete()) {
                logEntries.clear()
                activity?.runOnUiThread {
                    logAdapter.notifyDataSetChanged()
                }

                // Notify the SharedViewModel that the log has been cleared
                sharedViewModel.notifyLogCleared()

                showToast(getString(R.string.log_cleared_toast))
                clearedSuccessfully = true
            } else {
                showToast(getString(R.string.error_clearing_log_toast))
            }
        }
        if (!clearedSuccessfully && !file.exists()) { // If file didn't exist in the first place
             logEntries.clear() // Still clear the view if it was showing old data
             activity?.runOnUiThread {
                logAdapter.notifyDataSetChanged()
             }

             // Notify the SharedViewModel that the log has been cleared
             sharedViewModel.notifyLogCleared()

             showToast(getString(R.string.log_file_not_found_toast)) // Or a specific "log already empty/gone" message
        }
    }

    private fun showToast(message: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "LogViewerFragment"
    }
}

