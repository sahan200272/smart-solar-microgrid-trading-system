package com.example.microgridsystem

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.microgridsystem.models.NodeResponse
import com.example.microgridsystem.network.RetrofitClient
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class NearbyNodesActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: com.google.android.gms.maps.GoogleMap

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nearby_nodes)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: com.google.android.gms.maps.GoogleMap) {
        googleMap = map

        // Hardcoded test coordinates for now (e.g. Negombo) -
        // later this should come from the device's actual GPS location
        val userLat = 7.2083
        val userLng = 79.8358

        fetchNearbyNodes(userLat, userLng)
    }

    private fun fetchNearbyNodes(lat: Double, lng: Double) {
        RetrofitClient.instance.getNearbyNodes(lat, lng, 20.0)
            .enqueue(object : Callback<List<NodeResponse>> {
                override fun onResponse(
                    call: Call<List<NodeResponse>>,
                    response: Response<List<NodeResponse>>
                ) {
                    if (response.isSuccessful) {
                        val nodes = response.body() ?: emptyList()
                        displayNodesOnMap(nodes)
                    }
                }

                override fun onFailure(call: Call<List<NodeResponse>>, t: Throwable) {
                    // Log the error - in production you'd show a user-facing message
                    t.printStackTrace()
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

        // Center the camera on the first node found, if any exist
        if (nodes.isNotEmpty()) {
            val first = LatLng(nodes.first().latitude, nodes.first().longitude)
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(first, 12f))
        }
    }
}