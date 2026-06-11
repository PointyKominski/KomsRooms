package com.EdgeRip.KomsRooms

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.EdgeRip.KomsRooms.databinding.ItemServerBinding

class ServerListAdapter(
    private val servers: List<DiscoveryActivity.SnapcastServer>,
    private val onConnect: (DiscoveryActivity.SnapcastServer) -> Unit
) : RecyclerView.Adapter<ServerListAdapter.VH>() {

    inner class VH(val binding: ItemServerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun getItemCount() = servers.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val server = servers[position]
        holder.binding.tvServerName.text = server.name
        holder.binding.tvServerHost.text = server.host
        holder.binding.root.setOnClickListener { onConnect(server) }
    }
}
