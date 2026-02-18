package com.rdr.youtube2

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubscriptionsActivity : AppCompatActivity() {

    private lateinit var backButton: Button
    private lateinit var emptyText: TextView
    private lateinit var listView: RecyclerView
    private val subscriptions = mutableListOf<LocalChannelSubscription>()
    private lateinit var adapter: SubscriptionAdapter
    private var isHydratingIcons: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subscriptions)

        backButton = findViewById(R.id.subscriptions_back_button)
        emptyText = findViewById(R.id.subscriptions_screen_empty)
        listView = findViewById(R.id.subscriptions_screen_list)

        adapter = SubscriptionAdapter(subscriptions) { subscription ->
            openChannelDetail(subscription)
        }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        backButton.setOnClickListener {
            finish()
        }

        renderSubscriptions()
    }

    override fun onResume() {
        super.onResume()
        renderSubscriptions()
    }

    private fun renderSubscriptions() {
        val latest = ChannelSubscriptionsStore.list(this)
        subscriptions.clear()
        subscriptions.addAll(latest)
        adapter.notifyDataSetChanged()

        emptyText.visibility = if (subscriptions.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        hydrateMissingIcons(latest)
    }

    private fun hydrateMissingIcons(items: List<LocalChannelSubscription>) {
        if (isHydratingIcons) return
        val missing = items.filter { it.channelName.isNotBlank() && it.channelIconUrl.isBlank() }
        if (missing.isEmpty()) return

        isHydratingIcons = true
        lifecycleScope.launch {
            try {
                val refreshed = withContext(Dispatchers.IO) {
                    val updated = mutableListOf<LocalChannelSubscription>()
                    for (item in missing) {
                        val summary = PublicYouTubeFeedClient.resolveChannelSummary(
                            channelId = item.channelId,
                            channelName = item.channelName
                        ) ?: continue
                        val icon = summary.channelIconUrl.trim()
                        if (icon.isBlank()) continue
                        updated.add(
                            LocalChannelSubscription(
                                channelId = summary.channelId.ifBlank { item.channelId },
                                channelName = summary.channelName.ifBlank { item.channelName },
                                channelIconUrl = icon
                            )
                        )
                    }
                    updated
                }

                if (refreshed.isNotEmpty()) {
                    refreshed.forEach { subscription ->
                        ChannelSubscriptionsStore.subscribe(this@SubscriptionsActivity, subscription)
                    }
                    renderSubscriptions()
                }
            } finally {
                isHydratingIcons = false
            }
        }
    }

    private fun openChannelDetail(subscription: LocalChannelSubscription) {
        val intent = Intent(this, ChannelDetailActivity::class.java).apply {
            putExtra(ChannelDetailActivity.EXTRA_CHANNEL_NAME, subscription.channelName)
            putExtra(ChannelDetailActivity.EXTRA_CHANNEL_ID, subscription.channelId)
            putExtra(ChannelDetailActivity.EXTRA_CHANNEL_ICON_URL, subscription.channelIconUrl)
        }
        startActivity(intent)
    }

    private class SubscriptionAdapter(
        private val items: List<LocalChannelSubscription>,
        private val onClick: (LocalChannelSubscription) -> Unit
    ) : RecyclerView.Adapter<SubscriptionAdapter.SubscriptionViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubscriptionViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_subscription_channel, parent, false)
            return SubscriptionViewHolder(view)
        }

        override fun onBindViewHolder(holder: SubscriptionViewHolder, position: Int) {
            holder.bind(items[position], onClick)
        }

        override fun getItemCount(): Int = items.size

        class SubscriptionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val avatar: ImageView = view.findViewById(R.id.subscription_item_avatar)
            private val name: TextView = view.findViewById(R.id.subscription_item_name)

            fun bind(item: LocalChannelSubscription, onClick: (LocalChannelSubscription) -> Unit) {
                name.text = item.channelName
                Glide.with(itemView.context)
                    .load(item.channelIconUrl)
                    .placeholder(R.drawable.icon)
                    .error(R.drawable.icon)
                    .into(avatar)
                itemView.setOnClickListener { onClick(item) }
            }
        }
    }
}
