package com.getmusicbee.musicbeewifisync;

import static com.getmusicbee.musicbeewifisync.WifiSyncBaseActivity.PermissionsHandler.RW_ACCESS_REQUEST_ONLY;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import androidx.appcompat.app.AppCompatActivity;
import android.provider.Settings;
import android.view.MenuItem;

import androidx.annotation.NonNull;

abstract class WifiSyncBaseActivity extends AppCompatActivity {
    protected WifiSyncBaseActivity mainWindow = this;
    private boolean accessPermissionsGranted = false;
    protected File grantAccessToSdCard;
    protected int buttonTextEnabledColor;
    protected int buttonTextDisabledColor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Resources resources = getResources();
        buttonTextEnabledColor = resources.getColor(R.color.colorButtonTextEnabled);
        buttonTextDisabledColor = resources.getColor(R.color.colorButtonTextDisabled);
    }

    @Override
    protected void onDestroy() {
        mainWindow = null;
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent intent;
        switch (item.getItemId()) {
            case R.id.fullSyncMenuItem:
                if (!item.isChecked()) {
                    intent = new Intent(this, MainActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                }
                return true;
            case R.id.playlistSyncMenuItem:
                if (!item.isChecked()) {
                    intent = new Intent(this, PlaylistSyncActivity.class);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                }
                return true;
            case R.id.wifiSyncSettingsMenuItem:
                intent = new Intent(this, SettingsActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            case R.id.wifiSyncLogMenuItem:
                intent = new Intent(this, ViewErrorLogActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        boolean granted = (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        switch (requestCode) {
            case RW_ACCESS_REQUEST_ONLY:
                if (!granted) {
                    final WifiSyncBaseActivity mainWindow = this;
                    AlertDialog.Builder errorDialog = new AlertDialog.Builder(mainWindow);
                    errorDialog.setMessage(R.string.errorGrantRequired);
                    errorDialog.setNegativeButton(R.string.syncCancel, null);
                    errorDialog.setPositiveButton(R.string.syncRetry, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            PermissionsHandler.demandInternalStorageAccessPermissions(mainWindow);
                        }
                    });
                    errorDialog.show();
                }
                break;
            case PermissionsHandler.RW_ACCESS_REQUEST_AND_START_SYNC:
                if (granted) {
                    accessPermissionsGranted = true;
                    WifiSyncServiceSettings.accessPermissionsUri.set(null);
                    onStoragePermissionsApproved();
                }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if (resultCode == RESULT_OK) {
            WifiSyncServiceSettings.accessPermissionsUri.set(resultData.getData());
            if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.N || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) && !PermissionsHandler.isUserSelectedPermissionsPathValid(WifiSyncServiceSettings.accessPermissionsUri.get())) {
                AlertDialog.Builder errorDialog = new AlertDialog.Builder(this);
                String segment = WifiSyncServiceSettings.accessPermissionsUri.get().getLastPathSegment();
                errorDialog.setMessage(String.format(getString(R.string.errorInvalidPermmissionsFolder), segment.substring(segment.indexOf(':') + 1)));
                errorDialog.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        demandStorageAccessPermissions();
                    }
                });
                errorDialog.show();
            } else {
                WifiSyncServiceSettings.permissionPathToSdCardMapping.put(WifiSyncServiceSettings.accessPermissionsUri.get().getLastPathSegment(), grantAccessToSdCard.getPath());
                accessPermissionsGranted = true;
                getContentResolver().takePersistableUriPermission(WifiSyncServiceSettings.accessPermissionsUri.get(), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                WifiSyncServiceSettings.permissionsUpgraded = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
                onStoragePermissionsApproved();
            }
        }
    }

    protected boolean tryGetStorageAccessGrant() {
        if (accessPermissionsGranted) {
            return true;
        } else if (PermissionsHandler.tryGetStorageAccessGrant(this, WifiSyncServiceSettings.deviceStorageIndex, WifiSyncServiceSettings.accessPermissionsUri)) {
            accessPermissionsGranted = true;
            return true;
        } else {
            grantAccessToSdCard = FileStorageAccess.getSdCardFromIndex(this, WifiSyncServiceSettings.deviceStorageIndex);
            demandStorageAccessPermissions();
            return false;
        }
    }

    private void demandStorageAccessPermissions() {
        PermissionsHandler.demandStorageAccessPermissions(this, grantAccessToSdCard, new PermissionsHandler.Callback() {
            @Override
            public void processAccessRequestIntent(Intent accessRequestIntent) {
                startActivityForResult(accessRequestIntent, PermissionsHandler.RW_ACCESS_REQUEST_AND_START_SYNC);
            }
        });

    }

    protected void onStoragePermissionsApproved() {
    }

    static class PermissionsHandler {
        static final int RW_ACCESS_REQUEST_ONLY = 1;
        static final int RW_ACCESS_REQUEST_AND_START_SYNC = 2;

        static boolean isStorageAccessGranted(Activity context, int deviceStorageIndex) {
            AtomicReference<Uri> storageRootPermissionedUri = new AtomicReference<>();
            return tryGetStorageAccessGrant(context, deviceStorageIndex, storageRootPermissionedUri);
        }

        static boolean tryGetStorageAccessGrant(Activity context, int deviceStorageIndex, AtomicReference<Uri> storageRootPermissionedUri) {
            //ErrorHandler.logInfo("get grant", "device=" + deviceStorageIndex + ", " + WifiSyncServiceSettings.permissionPathToSdCardMapping.size());
            storageRootPermissionedUri.set(null);
            if (deviceStorageIndex == StorageCategory.INTERNAL && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
                return Environment.isExternalStorageManager();
            } else {
                File sdCard = FileStorageAccess.getSdCardFromIndex(context, deviceStorageIndex);
                if (sdCard == null) {
                    ErrorHandler.logError("grant", "Invalid sd card = " + deviceStorageIndex);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !WifiSyncServiceSettings.permissionsUpgraded) {
                    WifiSyncServiceSettings.permissionPathToSdCardMapping.clear();
                } else if (WifiSyncServiceSettings.permissionPathToSdCardMapping.size() > 0) {
                    List<UriPermission> permissions = context.getContentResolver().getPersistedUriPermissions();
                    if (permissions.size() == 0) {
                        // in case the user clears the permissions on the device
                        WifiSyncServiceSettings.permissionPathToSdCardMapping.clear();
                    } else {
                        String sdCardPath = sdCard.getPath();
                        Uri permissionUri = null;
                        int bestPathLevels = 255;
                        for (UriPermission permission : permissions) {
                            String rootId = permission.getUri().getLastPathSegment();
                            String path = WifiSyncServiceSettings.permissionPathToSdCardMapping.get(rootId);
                            //ErrorHandler.logInfo("grant", "perm uri=" + permission.getUri().toString() + ",mapped path=" + ((path == null) ? "null": path) + ",write=" + permission.isWritePermission() + ",sd card=" + sdCardPath);
                            if (path != null && path.equalsIgnoreCase(sdCardPath)) {
                                int levels = 0;
                                for (char c : rootId.toCharArray()) {
                                    if (c == '/') {
                                        levels++;
                                    }
                                }
                                if (levels < bestPathLevels) {
                                    bestPathLevels = levels;
                                    permissionUri = permission.getUri();
                                }
                            }
                        }
                        if (permissionUri != null) {
                            //ErrorHandler.logInfo("grant", "selected permission=" + permissionUri.toString());
                            storageRootPermissionedUri.set(permissionUri);
                            return true;
                        }
                    }
                }
                return false;
            }
        }

        static void demandInternalStorageAccessPermissions(Activity context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!Environment.isExternalStorageManager()) {
                    Intent accessRequestIntent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    context.startActivityForResult(accessRequestIntent, RW_ACCESS_REQUEST_ONLY);
                }
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(context, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, RW_ACCESS_REQUEST_ONLY);
            }
        }

        static void demandStorageAccessPermissions(Activity context, File sdCard, Callback callback) {
            if (sdCard == null) {
                ErrorHandler.logError("demand", "no sd card");
            } else {
                boolean internalStorage = (sdCard.getPath().equalsIgnoreCase(Environment.getExternalStorageDirectory().getPath()));
                if (internalStorage && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    ActivityCompat.requestPermissions(context, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, RW_ACCESS_REQUEST_AND_START_SYNC);
                } else {
                    StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                    Intent accessRequestIntent;
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        accessRequestIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            //"content://com.android.externalstorage.documents/tree/" + xxx + "%3A"
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                accessRequestIntent = storageManager.getStorageVolume(sdCard).createOpenDocumentTreeIntent();
                            }
                            accessRequestIntent.putExtra(DocumentsContract.EXTRA_PROMPT, context.getString(R.string.settingsSelectSdCard));
                        }
                        accessRequestIntent.putExtra("android.content.extra.SHOW_ADVANCED", true);
                        accessRequestIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        accessRequestIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    } else {
                        accessRequestIntent = storageManager.getStorageVolume(sdCard).createAccessIntent(null);
                    }
                    callback.processAccessRequestIntent(accessRequestIntent);
                }
            }
        }

        static boolean isUserSelectedPermissionsPathValid(Uri storageRootPermissionedUri) {
            String path = storageRootPermissionedUri.getLastPathSegment();
            return (path.lastIndexOf(':') == path.length() - 1);
        }

        interface Callback {
            void processAccessRequestIntent(Intent accessRequestIntent);
        }
    }
}
