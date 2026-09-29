// File:        OpsPrefs.kt
// Component:   Booking Views & Grid Operator Verification
// Description: Remembers the microgrid node a Grid Operator works at, shared by the
//              operator console filter and the QR scanner's "Verifying at" selection.
// Author:      Gunathilaka K.K.N.M.

package com.example.microgridsystem.data

import android.content.Context

object OpsPrefs {

    // Own preferences file, so signing out (which clears the session file) doesn't reset it.
    private const val PREFS_NAME = "ops_prefs"
    private const val KEY_NODE_ID = "ops_node_id"

    // Returns the saved node id, or null for "all nodes".
    fun getNodeId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_NODE_ID, null)
    }

    // Saves the selected node id. Null clears it.
    fun setNodeId(context: Context, nodeId: String?) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()

        if (nodeId.isNullOrBlank()) {
            editor.remove(KEY_NODE_ID)
        } else {
            editor.putString(KEY_NODE_ID, nodeId)
        }

        editor.apply()
    }
}
