package com.example.microgridsystem

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.models.NodeResponse
import com.example.microgridsystem.network.RetrofitClient
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class NearbyNodesActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_nodes)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        // Center the map on Sri Lanka by default, before we even know
        // if the API call succeeds - avoids a confusing "blank world map" look
        val defaultLocation = LatLng(7.2083, 79.8358)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(defaultLocation, 8f))

        fetchNearbyNodes(7.2083, 79.8358)
    }

    private fun fetchNearbyNodes(lat: Double, lng: Double) {

        // TEMPORARY: hardcoded token for testing until mobile login exists.
        // Replace this with SharedPreferences retrieval once login is built.
        val token = "PASTE_YOUR_REAL_TOKEN_HERE"

        val authHeader = "Bearer $token"

        // Retrieve the JWT token saved after login.
        // Adjust this once you confirm how Member A's login screen
        // stores the token (SharedPreferences key name, etc.)
        //val sharedPrefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        //val token = sharedPrefs.getString("jwt_token", null)

        //android.util.Log.d("NearbyNodes", "Token retrieved: $token")

        //if (token == null) {
            //android.util.Log.d("NearbyNodes", "No token found - stopping here")
            //return
        //}

        //val authHeader = "Bearer $token"

        RetrofitClient.instance.getNearbyNodes(authHeader, lat, lng, 20.0)
            .enqueue(object : Callback<List<NodeResponse>> {
                override fun onResponse(
                    call: Call<List<NodeResponse>>,
                    response: Response<List<NodeResponse>>
                ) {
                    android.util.Log.d("NearbyNodes", "Response code: ${response.code()}")
                    if (response.isSuccessful) {
                        val nodes = response.body() ?: emptyList()
                        android.util.Log.d("NearbyNodes", "Nodes received: ${nodes.size}")
                        displayNodesOnMap(nodes)
                    } else {
                        // Log the failure - could be 401 if token expired/invalid
                        //println("API call failed: ${response.code()}")
                        android.util.Log.e("NearbyNodes", "Failed: ${response.code()} - ${response.errorBody()?.string()}")
                    }
                }

                override fun onFailure(call: Call<List<NodeResponse>>, t: Throwable) {
                    android.util.Log.e("NearbyNodes", "Network failure", t)
                }
            })
    }

    private fun displayNodesOnMap(nodes: List<NodeResponse>) {
        for (node in nodes) {
            val position = LatLng(node.latitude, node.longitude)
            googleMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .title(node.stationName)
                    .snippet("${node.availableBatterySlots}/${node.totalBatterySlots} slots available")
            )
        }

        if (nodes.isNotEmpty()) {
            val first = LatLng(nodes.first().latitude, nodes.first().longitude)
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(first, 12f))
        }
    }
}