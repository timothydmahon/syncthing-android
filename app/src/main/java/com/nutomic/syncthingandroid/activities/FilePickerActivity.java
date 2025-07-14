package com.nutomic.syncthingandroid.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.common.collect.Sets;
import com.nutomic.syncthingandroid.R;
import com.nutomic.syncthingandroid.SyncthingApp;
import com.nutomic.syncthingandroid.model.Folder;
import com.nutomic.syncthingandroid.service.Constants;
import com.nutomic.syncthingandroid.service.RestApi;
import com.nutomic.syncthingandroid.util.ConfigRouter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Activity that allows selecting a specific files in the local file system.
 */
public class FilePickerActivity extends SyncthingActivity {

    private static final String TAG = "FilePickerActivity";

    public static final String EXTRA_FOLDER_ID =
            "com.github.catfriend1.syncthingandroid.activities.FilePickerActivity.FOLDER_ID";
    private static final String ST_INDEX_DIR = ".stfileindex";
    private static final String ST_INDEX_FILE = "_file_index";
    private static final ArrayList<String> IGNORE_DEFAULTS = new ArrayList<>(Arrays.asList("!/.stfileindex", "*"));
    private ConfigRouter mConfig;
    private Folder mFolder;
    private String mFolderId;

    private String mFolderName;
    private String mFolderPath;
    private TextView mCurrentFolder;
    private ListView mListView;
    private FileAdapter mFilesAdapter;

    public static Intent createIntent(Context context, String folderId) {
        Intent intent = new Intent(context, FilePickerActivity.class);

        if (!TextUtils.isEmpty(folderId)) {
            intent.putExtra(EXTRA_FOLDER_ID, folderId);
        }

        return intent;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.file_picker, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.save).setTitle(R.string.save_title);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.save) {
            saveSelectedFiles();
            deleteUnselectedFiles();
            rescanFolder();
            Toast.makeText(this, R.string.file_picker_saved, Toast.LENGTH_SHORT)
                    .show();
            finish();
            return true;
        } else if (itemId == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        mConfig = new ConfigRouter(FilePickerActivity.this);
        super.onCreate(savedInstanceState);
        ((SyncthingApp) getApplication()).component().inject(this);

        setContentView(R.layout.activity_file_picker);


        mFolderId = getIntent().getStringExtra(EXTRA_FOLDER_ID);
        mFolder = mConfig.getFolder(null, mFolderId);
        mFolderName = mFolder.label;
        mFolderPath = mFolder.path;

        mCurrentFolder = findViewById(R.id.currentFolder);
        mListView = findViewById(android.R.id.list);
        mListView.setEmptyView(findViewById(android.R.id.empty));
        mFilesAdapter = new FileAdapter(this);
        mListView.setAdapter(mFilesAdapter);

        displayFiles();
    }

    private void saveSelectedFiles() {
        ArrayList<String> selectedFiles = mFilesAdapter.getSelectedFiles();
        if (selectedFiles != null) {
            Collections.sort(selectedFiles);
        }
        selectedFiles.addAll(IGNORE_DEFAULTS);
        mConfig.writeToIgnore(mFolderPath, (String[]) selectedFiles.toArray());
    }

    private void deleteUnselectedFiles() {
        ArrayList<String> unselectedFiles = mFilesAdapter.getUnselectedFiles();
        for (String file: unselectedFiles){
            File fileToDelete = new File(mFolderPath, file);
            if (fileToDelete.exists()) {
                fileToDelete.delete();
                mFilesAdapter.removeUnselected(file);
            }
        }
    }

    private void rescanFolder() {
        RestApi restApi = getApi();
        if (restApi == null || !restApi.isConfigLoaded()) {
            Log.e(TAG, "rescanFolder skipped because Syncthing is not running.");
            return;
        }
        restApi.rescanFolder(mFolderId);
    }

    private ArrayList<String> getCurrentSelectedFiles() {
        ArrayList<String> selectedFiles = new ArrayList<>();
        String [] rawIgnoreFiles = readFileData(mFolderPath, Constants.FILENAME_STIGNORE);
        if (rawIgnoreFiles == null) {
            return selectedFiles;
        }
        for (String file: rawIgnoreFiles) {
            if (!IGNORE_DEFAULTS.contains(file) && file.length() > 1) {
                selectedFiles.add(file);
            }
        }
        return selectedFiles;
    }
    private ArrayList<String> getIndexedFiles() {
        String [] rawIndexedFiles = readFileData(mFolderPath + "/" + ST_INDEX_DIR, ST_INDEX_FILE);
        if (rawIndexedFiles == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(rawIndexedFiles));
    }

    private String[] readFileData(String folderPath, String fileName) {
        String [] fileData = null;
        File file = new File(folderPath, fileName);
        if (!file.exists()) {
            return fileData;
        }
        try (FileInputStream fileInputStream = new FileInputStream(file)) {
            try {
                byte[] data = new byte[(int) file.length()];
                fileInputStream.read(data);
                fileData = new String(data, StandardCharsets.UTF_8).replaceAll("\\r", "").split("\n");
            } catch (IOException e) {
                Log.e(TAG, "getFolderIgnoreList: Failed to read '" + folderPath + "/" + fileName + "' #1", e);
            }
        } catch (IOException e) {
            Log.e(TAG, "getFolderIgnoreList: Failed to read '" + folderPath + "/" + fileName + "' #2", e);
        }
        return fileData;
    }

    /**
     * Refreshes the ListView to show the contents of the folder in {@code }mLocation.peek()}.
     */
    private void displayFiles() {
        mCurrentFolder.setText(mFolderName);
        mFilesAdapter.clear();

        ArrayList<String> availableFiles = getIndexedFiles();
        mFilesAdapter.addAll(Sets.newTreeSet(availableFiles));

        mListView.setAdapter(mFilesAdapter);
    }

    private class FileAdapter extends ArrayAdapter<String> {

        private ArrayList<String> mSelectedFiles;
        private ArrayList<String> mUnselectedFiles;
        public FileAdapter(Context context) {
            super(context, android.R.layout.simple_list_item_multiple_choice);
        }

        @Override
        @NonNull
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            convertView = super.getView(position, convertView, parent);
            String fileNameText = getItem(position);
            String ignoreRule = "!" + fileNameText;
            CheckedTextView fileName = convertView.findViewById(android.R.id.text1);
            fileName.setText(fileNameText);

            if (mUnselectedFiles == null) {
                mUnselectedFiles = new ArrayList<>();
            }
            if (mSelectedFiles == null){
                mSelectedFiles = getCurrentSelectedFiles();
            }
            if (mSelectedFiles.contains(ignoreRule)) {
                fileName.setChecked(true);
            }
            fileName.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    fileName.toggle();
                    if (fileName.isChecked()){
                        mSelectedFiles.add(ignoreRule);
                        mUnselectedFiles.remove(fileNameText.substring(1));
                    } else {
                        mSelectedFiles.remove(ignoreRule);
                        mUnselectedFiles.add(fileNameText.substring(1));
                    }
                }
            });
            return convertView;
        }

        public void removeUnselected(String fileName) {
            mUnselectedFiles.remove(fileName);
        }

        public ArrayList<String> getSelectedFiles() {
            if (mSelectedFiles == null) {
                return new ArrayList<>();
            }
            return mSelectedFiles;
        }
        public ArrayList<String> getUnselectedFiles() {
            if (mUnselectedFiles == null) {
                return new ArrayList<>();
            }
            return mUnselectedFiles;
        }
    }

}
