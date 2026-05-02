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
 */
public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.FileViewHolder> {

    private List<FileItem> items;
    private OnFileClickListener listener;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

    public interface OnFileClickListener {
        void onFileClick(FileItem item);
    }

    public FileListAdapter(List<FileItem> items, OnFileClickListener listener) {
        this.items = items != null ? items : new ArrayList<>();
        this.listener = listener;
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

        // Click on the entire item opens file/folder
        holder.itemContainer.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFileClick(item);
            }
        });

        // Long press to show context actions (rename, delete)
        holder.itemContainer.setOnLongClickListener(v -> {
            showContextMenu(holder, item);
            return true;
        });
    }

    private void showContextMenu(FileViewHolder holder, FileItem item) {
        // Delegate to activity via callback
        if (holder.itemView.getContext() instanceof FileManagerActivity) {
            FileManagerActivity activity = (FileManagerActivity) holder.itemView.getContext();

            String[] options;
            if (item.isDirectory()) {
                options = new String[]{
                        activity.getString(R.string.rename),
                        activity.getString(R.string.delete)
                };
            } else {
                options = new String[]{
                        activity.getString(R.string.rename),
                        activity.getString(R.string.delete)
                };
            }

            new android.app.AlertDialog.Builder(activity)
                    .setTitle(item.getName())
                    .setItems(options, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                activity.showRenameDialog(item);
                                break;
                            case 1:
                                activity.showDeleteDialog(item);
                                break;
                        }
                    })
                    .show();
        }
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
