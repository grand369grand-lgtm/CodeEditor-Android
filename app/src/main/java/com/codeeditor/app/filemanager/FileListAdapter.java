package com.codeeditor.app.filemanager;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.codeeditor.app.R;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView Adapter for file list.
 * iOS-style file item design with icon, name, details, and action buttons.
 * Supports both single click and long-press callbacks.
 */
public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.FileViewHolder> {

    private List<FileItem> items;
    private OnFileClickListener clickListener;
    private OnFileLongClickListener longClickListener;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

    public interface OnFileClickListener {
        void onFileClick(FileItem item);
    }

    public interface OnFileLongClickListener {
        void onFileLongClick(FileItem item);
    }

    public FileListAdapter(List<FileItem> items, OnFileClickListener clickListener,
                           OnFileLongClickListener longClickListener) {
        this.items = items != null ? items : new ArrayList<>();
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileItem item = items.get(position);

        holder.ivIcon.setImageResource(item.getIconResource());
        holder.tvFileName.setText(item.getName());

        // Set file details
        String details;
        if (item.isDirectory()) {
            details = "Folder";
        } else {
            details = item.getFormattedSize() + " · " + dateFormat.format(new Date(item.getLastModified()));
        }
        holder.tvFileDetails.setText(details);

        // Show SAF indicator for files accessed via SAF
        if (item.isSaf()) {
            holder.tvFileDetails.setText(details + " · SAF");
        }

        // Set file extension badge
        String ext = item.getExtension();
        if (!ext.isEmpty() && !item.isDirectory()) {
            holder.tvExtension.setVisibility(View.VISIBLE);
            holder.tvExtension.setText(ext.toUpperCase());
        } else {
            holder.tvExtension.setVisibility(item.isDirectory() ? View.GONE : View.VISIBLE);
            if (!item.isDirectory()) {
                holder.tvExtension.setText("FILE");
            }
        }

        // Single click opens file/folder
        holder.itemContainer.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onFileClick(item);
            }
        });

        // Long press shows context menu
        holder.itemContainer.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onFileLongClick(item);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public void updateItems(List<FileItem> newItems) {
        this.items = newItems != null ? newItems : new ArrayList<>();
        notifyDataSetChanged();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        LinearLayout itemContainer;
        ImageView ivIcon;
        TextView tvFileName;
        TextView tvFileDetails;
        TextView tvExtension;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            itemContainer = itemView.findViewById(R.id.item_container);
            ivIcon = itemView.findViewById(R.id.iv_file_icon);
            tvFileName = itemView.findViewById(R.id.tv_file_name);
            tvFileDetails = itemView.findViewById(R.id.tv_file_details);
            tvExtension = itemView.findViewById(R.id.tv_extension);
        }
    }
}
