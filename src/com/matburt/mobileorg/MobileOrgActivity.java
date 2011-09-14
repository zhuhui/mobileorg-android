package com.matburt.mobileorg;

import java.io.BufferedReader;
import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import android.app.Activity;
import android.app.Dialog;
import android.app.ListActivity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;

import com.matburt.mobileorg.Capture.Capture;
import com.matburt.mobileorg.Capture.ViewNodeDetailsActivity;
import com.matburt.mobileorg.Error.ErrorReporter;
import com.matburt.mobileorg.Error.ReportableError;
import com.matburt.mobileorg.Parsing.Node;
import com.matburt.mobileorg.Parsing.OrgFileParser;
import com.matburt.mobileorg.Settings.SettingsActivity;
import com.matburt.mobileorg.Synchronizers.DropboxSynchronizer;
import com.matburt.mobileorg.Synchronizers.SDCardSynchronizer;
import com.matburt.mobileorg.Synchronizers.Synchronizer;
import com.matburt.mobileorg.Synchronizers.WebDAVSynchronizer;

public class MobileOrgActivity extends ListActivity {
	private static final int OP_MENU_SETTINGS = 1;
	private static final int OP_MENU_SYNC = 2;
	private static final int OP_MENU_OUTLINE = 3;
	private static final int OP_MENU_CAPTURE = 4;
	
	private static final int RUN_PARSER = 3;

	@SuppressWarnings("unused")
	private static final String LT = "MobileOrg";

	private int displayIndex;
	private ProgressDialog syncDialog;
	private MobileOrgDatabase appdb;
	private ReportableError syncError;
	private Dialog newSetupDialog;
	private boolean newSetupDialog_shown = false;
	private SharedPreferences appSettings;
	final Handler syncHandler = new Handler();
	private ArrayList<Integer> origSelection = null;
	private boolean first = true;

	final Runnable syncUpdateResults = new Runnable() {
		public void run() {
			postSynchronize();
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.appdb = new MobileOrgDatabase((Context) this);
		appSettings = PreferenceManager
				.getDefaultSharedPreferences(getBaseContext());

		registerForContextMenu(getListView());

		if (this.appSettings.getString("syncSource", "").equals("")
				|| (this.appSettings.getString("syncSource", "").equals(
						"webdav") && this.appSettings.getString("webUrl", "")
						.equals(""))
				|| (this.appSettings.getString("syncSource", "").equals(
						"sdcard") && this.appSettings.getString(
						"indexFilePath", "").equals(""))) {
			this.onShowSettings();
		}

		// Start the background sync service (if it isn't already) and if we
		// have turned on background sync
		if (this.appSettings.getBoolean("doAutoSync", false)) {
			Intent serviceIntent = new Intent();
			serviceIntent.setAction("com.matburt.mobileorg.SYNC_SERVICE");
			this.startService(serviceIntent);
		}
	}

	@Override
	public void onDestroy() {
		this.appdb.close();
		super.onDestroy();
	}

	public void runParser() {
		MobileOrgApplication appInst = (MobileOrgApplication) this
				.getApplication();
		HashMap<String, String> allOrgList = this.appdb.getOrgFiles();
		if (allOrgList.isEmpty()) {
			return;
		}
		String storageMode = this.getStorageLocation();
		String userSynchro = this.appSettings.getString("syncSource", "");
		String orgBasePath = "";

		if (userSynchro.equals("sdcard")) {
			String indexFile = this.appSettings.getString("indexFilePath", "");
			File fIndexFile = new File(indexFile);
			orgBasePath = fIndexFile.getParent() + "/";
		} else {
			orgBasePath = Environment.getExternalStorageDirectory()
					.getAbsolutePath() + "/mobileorg/";
		}

		OrgFileParser ofp = new OrgFileParser(allOrgList, storageMode,
				userSynchro, this.appdb, orgBasePath);
		try {
			ofp.parse();
			appInst.rootNode = ofp.rootNode;
			appInst.edits = ofp.parseEdits();
			Collections.sort(appInst.rootNode.children, Node.comparator);
		} catch (Throwable e) {
			ErrorReporter.displayError(
					this,
					"An error occurred during parsing, try re-syncing: "
							+ e.toString());
		}
	}

	public void showNewUserWindow() {
		if (this.newSetupDialog_shown) {
			this.newSetupDialog.cancel();
		}
		newSetupDialog = new Dialog(this);
		newSetupDialog.setContentView(R.layout.empty_main);
		Button syncButton = (Button) newSetupDialog
				.findViewById(R.id.dialog_run_sync);
		syncButton.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				runSynchronizer();
			}
		});
		Button settingsButton = (Button) newSetupDialog
				.findViewById(R.id.dialog_show_settings);
		settingsButton.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				onShowSettings();
			}
		});
		newSetupDialog.setTitle("Synchronize Org Files");
		newSetupDialog.show();
		this.newSetupDialog_shown = true;
	}

	@Override
	public void onResume() {
		Log.d("MobileOrg" + this, "onResume");
		super.onResume();
		Intent nodeIntent = getIntent();
		MobileOrgApplication appInst = (MobileOrgApplication) this
				.getApplication();
		ArrayList<Integer> intentNodePath = nodeIntent
				.getIntegerArrayListExtra("nodePath");
		if (intentNodePath != null) {
			appInst.nodeSelection = copySelection(intentNodePath);
			nodeIntent.putIntegerArrayListExtra("nodePath", null);
			Log.d("MobileOrg" + this, "resume first=" + first
					+ " had nodePath="
					+ nodeSelectionStr(appInst.nodeSelection));
		} else {
			Log.d("MobileOrg" + this, "resume first=" + first
					+ " restoring original selection"
					+ nodeSelectionStr(this.origSelection));
			appInst.nodeSelection = copySelection(this.origSelection);
		}
		Log.d("MobileOrg" + this, "afteResume appInst.nodeSelection="
				+ nodeSelectionStr(appInst.nodeSelection));

		populateDisplay();
	}

	public void populateDisplay() {
		MobileOrgApplication appInst = (MobileOrgApplication) this
				.getApplication();
		if (appInst.rootNode == null) {
			this.runParser();
		}

		HashMap<String, String> allOrgList = this.appdb.getOrgFiles();
		if (allOrgList.isEmpty()) {
			this.showNewUserWindow();
		} else if (this.newSetupDialog_shown) {
			newSetupDialog_shown = false;
			newSetupDialog.cancel();
		}

		if (first) {
			this.setListAdapter(new OrgViewAdapter(this, appInst.rootNode,
					appInst.nodeSelection, appInst.edits, this.appdb.getTodos()));
			if (appInst.nodeSelection != null) {
				this.origSelection = copySelection(appInst.nodeSelection);
			} else {
				this.origSelection = null;
			}
			Log.d("MobileOrg" + this, " first redisplay, origSelection="
					+ nodeSelectionStr(this.origSelection));

			getListView().setSelection(displayIndex);
			first = false;

			// setTitle(generateTitle());

		}
	}

	String generateTitle() {
		MobileOrgApplication appInst = (MobileOrgApplication) this
				.getApplication();
		String title = "";

		if (appInst.nodeSelection != null) {
			ArrayList<Integer> nodeSelectionBackup = new ArrayList<Integer>();
			for (Integer item : appInst.nodeSelection)
				nodeSelectionBackup.add(item);

			while (nodeSelectionBackup.size() > 0) {
				title = appInst.getNode(nodeSelectionBackup).nodeTitle + "$"
						+ title;
				nodeSelectionBackup.remove(nodeSelectionBackup.size() - 1);
			}
		}
		return title;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		menu.add(0, MobileOrgActivity.OP_MENU_OUTLINE, 0, R.string.menu_outline);
		menu.add(0, MobileOrgActivity.OP_MENU_CAPTURE, 0, R.string.menu_capture);
		menu.add(0, MobileOrgActivity.OP_MENU_SYNC, 0, R.string.menu_sync);
		menu.add(0, MobileOrgActivity.OP_MENU_SETTINGS, 0,
				R.string.menu_settings);
		return true;
	}
	
	private static final int OP_CMENU_VIEW = 0;
	private static final int OP_CMENU_EDIT = 1;
	
	@Override
	public void onCreateContextMenu(ContextMenu menu, View v,
			ContextMenuInfo menuInfo) {
		super.onCreateContextMenu(menu, v, menuInfo);
		menu.add(0, OP_CMENU_VIEW, 0, "View Node");
		menu.add(0, OP_CMENU_EDIT, 0, "Edit Node");
	}

	@Override
	public boolean onContextItemSelected(MenuItem item) {
		AdapterContextMenuInfo info = (AdapterContextMenuInfo) item
				.getMenuInfo();
		Node n = (Node) getListAdapter().getItem(info.position);

		Intent textIntent = new Intent();

		switch (item.getItemId()) {
		case OP_CMENU_VIEW:
			textIntent.setClass(this, SimpleTextDisplay.class);
			String txtValue = n.nodeTitle + "\n\n" + n.payload;
			textIntent.putExtra("txtValue", txtValue);
			textIntent.putExtra("nodeTitle", n.name);
			break;

		case OP_CMENU_EDIT:
			textIntent.setClass(this, ViewNodeDetailsActivity.class);
			textIntent.putExtra("actionMode", "edit");
		
			MobileOrgApplication appInst = (MobileOrgApplication) this
					.getApplication();
			textIntent.putIntegerArrayListExtra("nodePath", appInst.nodeSelection);
			break;
		}
		startActivity(textIntent);
		return false;
	}

	static private ArrayList<Integer> copySelection(ArrayList<Integer> selection) {
		if (selection == null)
			return null;
		else
			return new ArrayList<Integer>(selection);
	}

	static String nodeSelectionStr(ArrayList<Integer> nodes) {
		if (nodes != null) {
			String tmp = "";

			for (Integer i : nodes) {
				if (tmp.length() > 0)
					tmp += ",";
				tmp += i;
			}
			return tmp;
		}
		return "null";
	}

	@Override
	public void onListItemClick(ListView l, View v, int position, long id) {
		MobileOrgApplication appInst = (MobileOrgApplication) this
				.getApplication();

		Log.d("MobileOrg" + this, "onListItemClick position=" + position);
		appInst.pushSelection(position);
		Node thisNode = appInst.getSelectedNode();
		Log.d("MobileOrg" + this, "appInst.nodeSelection="
				+ nodeSelectionStr(appInst.nodeSelection));

		if (thisNode.encrypted && !thisNode.parsed) {
			// if suitable APG version is installed
			if (Encryption.isAvailable((Context) this)) {
				// retrieve the encrypted file data
				String userSynchro = this.appSettings.getString("syncSource",
						"");
				String orgBasePath = "";
				if (userSynchro.equals("sdcard")) {
					String indexFile = this.appSettings.getString(
							"indexFilePath", "");
					File fIndexFile = new File(indexFile);
					orgBasePath = fIndexFile.getParent() + "/";
				} else {
					orgBasePath = Environment.getExternalStorageDirectory()
							.getAbsolutePath() + "/mobileorg/";
				}

				byte[] rawData = OrgFileParser.getRawFileData(orgBasePath,
						thisNode.name);
				// and send it to APG for decryption
				Encryption.decrypt(this, rawData);
			} else {
				appInst.popSelection();
			}
			return;
		}

		if (thisNode.children.size() < 1) {
			displayIndex = appInst.lastIndex();
			Log.d("MobileOrg" + this,
					"no subnodes, popped selection, displayIndex="
							+ displayIndex);
			appInst.popSelection();
			if (thisNode.todo.equals("") && thisNode.priority.equals("")) {
				Intent textIntent = new Intent(this, SimpleTextDisplay.class);
				String docBuffer = thisNode.name + "\n\n" + thisNode.payload;

				textIntent.putExtra("txtValue", docBuffer);
				startActivity(textIntent);
			} else {
				Intent dispIntent = new Intent(this,
						ViewNodeDetailsActivity.class);

				dispIntent.putExtra("actionMode", "edit");
				Log.d("MobileOrg" + this, "Before edit appInst.nodeSelection="
						+ nodeSelectionStr(appInst.nodeSelection));
				dispIntent.putIntegerArrayListExtra("nodePath",
						appInst.nodeSelection);
				Log.d("MobileOrg" + this, "After push appInst.nodeSelection="
						+ nodeSelectionStr(appInst.nodeSelection));
				appInst.pushSelection(position);
				startActivity(dispIntent);
			}
		} else {
			expandSelection(appInst.nodeSelection);
		}
	}

	public void expandSelection(ArrayList<Integer> selection) {
		Intent dispIntent = new Intent(this, MobileOrgActivity.class);
		dispIntent.putIntegerArrayListExtra("nodePath", selection);
		startActivityForResult(dispIntent, 1);
	}

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		Log.d("MobileOrg" + this, "onActivityResult");
		MobileOrgApplication appInst = (MobileOrgApplication) this
				.getApplication();
		if (requestCode == RUN_PARSER) {
			this.runParser();
		} else if (requestCode == Encryption.DECRYPT_MESSAGE) {
			if (resultCode != Activity.RESULT_OK || data == null) {
				appInst.popSelection();
				return;
			}

			Node thisNode = appInst.getSelectedNode();
			String userSynchro = this.appSettings.getString("syncSource", "");
			String orgBasePath = "";
			if (userSynchro.equals("sdcard")) {
				String indexFile = this.appSettings.getString("indexFilePath",
						"");
				File fIndexFile = new File(indexFile);
				orgBasePath = fIndexFile.getParent() + "/";
			} else {
				orgBasePath = Environment.getExternalStorageDirectory()
						.getAbsolutePath() + "/mobileorg/";
			}
			String decryptedData = data
					.getStringExtra(Encryption.EXTRA_DECRYPTED_MESSAGE);
			OrgFileParser ofp = new OrgFileParser(appdb.getOrgFiles(),
					getStorageLocation(), userSynchro, appdb, orgBasePath);

			ofp.parse(thisNode, new BufferedReader(new StringReader(
					decryptedData)));
			expandSelection(appInst.nodeSelection);
		} else {
			displayIndex = appInst.lastIndex();
			appInst.popSelection();
		}
	}

	public boolean onShowSettings() {
		Intent settingsIntent = new Intent(this, SettingsActivity.class);
		startActivity(settingsIntent);
		return true;
	}

	public void runSynchronizer() {
		String userSynchro = this.appSettings.getString("syncSource", "");
		final Synchronizer appSync;
		if (userSynchro.equals("webdav")) {
			appSync = new WebDAVSynchronizer(this);
		} else if (userSynchro.equals("sdcard")) {
			appSync = new SDCardSynchronizer(this);
		} else if (userSynchro.equals("dropbox")) {
			appSync = new DropboxSynchronizer(this);
		} else {
			this.onShowSettings();
			return;
		}

		if (!appSync.checkReady()) {
			Toast error = Toast
					.makeText(
							(Context) this,
							"You have not fully configured the synchronizer.  Make sure you visit the 'Configure Synchronizer Settings' in the Settings menu",
							Toast.LENGTH_LONG);
			error.show();
			this.onShowSettings();
			return;
		}

		Thread syncThread = new Thread() {
			public void run() {
				try {
					syncError = null;
					appSync.pull();
					appSync.push();
					Log.d("MobileOrg" + this, "Finished parsing...");
				} catch (ReportableError e) {
					syncError = e;
				} finally {
					appSync.close();
				}
				syncHandler.post(syncUpdateResults);
			}
		};
		syncThread.start();
		syncDialog = ProgressDialog.show(this, "",
				getString(R.string.sync_wait), true);
	}

	public boolean runCapture() {
		Intent captureIntent = new Intent(this, Capture.class);
		captureIntent.putExtra("actionMode", "create");
		startActivityForResult(captureIntent, 3);
		return true;
	}

	public void postSynchronize() {
		syncDialog.dismiss();
		if (this.syncError != null) {
			ErrorReporter.displayError(this, this.syncError);
		} else {
			this.runParser();
			this.onResume();
		}
	}

	/* Handles item selections */
	public boolean onOptionsItemSelected(MenuItem item) {
		Log.d("MobileOrg" + this, "onOptionsItemSelected");
		switch (item.getItemId()) {
		case MobileOrgActivity.OP_MENU_SYNC:
			this.runSynchronizer();
			return true;
		case MobileOrgActivity.OP_MENU_SETTINGS:
			return this.onShowSettings();
		case MobileOrgActivity.OP_MENU_OUTLINE:
			Intent dispIntent = new Intent(this, MobileOrgActivity.class);
			dispIntent.putIntegerArrayListExtra("nodePath",
					new ArrayList<Integer>());
			startActivity(dispIntent);
			return true;
		case MobileOrgActivity.OP_MENU_CAPTURE:
			return this.runCapture();
		}
		return false;
	}

	public String getStorageLocation() {
		return this.appSettings.getString("storageMode", "");
	}
}
