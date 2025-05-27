package com.example.scan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.scan.databinding.ItemLogEntryBinding

class LogAdapter(private val logEntries: List<LogEntry>) :
    RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding =
            ItemLogEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val logEntry = logEntries[position]
        holder.bind(logEntry)
    }

    override fun getItemCount(): Int = logEntries.size

    class LogViewHolder(private val binding: ItemLogEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(logEntry: LogEntry) {
            binding.barcodeTextView.text = logEntry.barcode
            binding.timestampTextView.text = logEntry.timestamp
        }
    }
}

