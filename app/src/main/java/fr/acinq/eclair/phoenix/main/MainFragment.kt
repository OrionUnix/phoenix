/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.phoenix.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.acinq.eclair.phoenix.BaseFragment
import fr.acinq.eclair.phoenix.R
import fr.acinq.eclair.phoenix.databinding.FragmentMainBinding
import fr.acinq.eclair.phoenix.events.PaymentPending
import fr.acinq.eclair.phoenix.utils.InAppNotifications
import fr.acinq.eclair.phoenix.utils.KitNotInitialized
import fr.acinq.eclair.phoenix.utils.Prefs
import fr.acinq.eclair.phoenix.utils.Wallet
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.text.DateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.collections.HashSet

class MainFragment : BaseFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

  override val log: Logger = LoggerFactory.getLogger(this::class.java)

  private lateinit var model: MainViewModel
  private lateinit var mBinding: FragmentMainBinding

  private lateinit var paymentsAdapter: PaymentsAdapter
  //  private lateinit var paymentsListAdapter: PaymentsListAdapter
  private lateinit var paymentsManager: RecyclerView.LayoutManager

  private lateinit var notificationsAdapter: NotificationsAdapter
  private lateinit var notificationsManager: RecyclerView.LayoutManager

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    mBinding = FragmentMainBinding.inflate(inflater, container, false)
    mBinding.lifecycleOwner = this

    // init payment recycler view
    paymentsManager = LinearLayoutManager(context)
    paymentsAdapter = PaymentsAdapter(ArrayList())
    mBinding.paymentList.apply {
      setHasFixedSize(true)
      layoutManager = paymentsManager
      adapter = paymentsAdapter
    }
    //    paymentsListAdapter = PaymentsListAdapter()
    //    mBinding.paymentList.apply {
    //      setHasFixedSize(true)
    //      layoutManager = paymentsManager
    //      adapter = paymentsListAdapter
    //    }


    // init notification recycler view
    notificationsManager = LinearLayoutManager(context)
    notificationsAdapter = NotificationsAdapter(HashSet())
    mBinding.notificationList.apply {
      setHasFixedSize(true)
      layoutManager = notificationsManager
      adapter = notificationsAdapter
    }

    return mBinding.root
  }

  override fun onActivityCreated(savedInstanceState: Bundle?) {
    super.onActivityCreated(savedInstanceState)
    appKit.nodeData.observe(viewLifecycleOwner, Observer { nodeData ->
      nodeData?.let {mBinding.balance.setAmount(it.balance)}
    })
    appKit.pendingSwapIn.observe(viewLifecycleOwner, Observer { isPending ->
      mBinding.swapInInfo.visibility = if (isPending) View.VISIBLE else View.GONE
    })
    mBinding.appKitModel = appKit

    model = ViewModelProvider(this).get(MainViewModel::class.java)
    model.payments.observe(viewLifecycleOwner, Observer {
      //      paymentsListAdapter.submitList(it)
      paymentsAdapter.update(it)
    })
    appKit.notifications.observe(viewLifecycleOwner, Observer {
      notificationsAdapter.update(it)
    })
  }

  override fun onStart() {
    super.onStart()
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }
    Wallet.hideKeyboard(context, mBinding.main)

    context?.let {
      refreshNotifications(it)
    }

    mBinding.settingsButton.setOnClickListener { findNavController().navigate(R.id.action_main_to_settings) }
    mBinding.receiveButton.setOnClickListener { findNavController().navigate(R.id.action_main_to_receive) }
    mBinding.sendButton.setOnClickListener { findNavController().navigate(R.id.action_main_to_read_input) }
    mBinding.helpButton.setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://acinq.co/phoenix"))) }

    refreshPaymentList()
  }

  override fun onStop() {
    super.onStop()
    EventBus.getDefault().unregister(this)
  }

  override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
    context?.let { refreshNotifications(it) }
  }

  @Subscribe(threadMode = ThreadMode.MAIN)
  fun handleEvent(event: PaymentPending) {
    refreshPaymentList()
  }

  private fun refreshPaymentList() {
    lifecycleScope.launch(CoroutineExceptionHandler { _, e ->
      if (e !is KitNotInitialized) {
        log.error("error when fetching payments: ", e)
      }
    }) {
      model.payments.value = appKit.listPayments()
    }
  }

  private fun refreshNotifications(context: Context) {
    checkWalletIsSecure(context)
    checkMnemonics(context)
    checkBackgroundWorkerCanRun(context)
  }

  /**
   * If the background channels watcher has not run since (now) - (DELAY_BEFORE_BACKGROUND_WARNING), we consider that the device is
   * blocking this application from working in background, and show a notification.
   *
   * Some devices vendors are known to aggressively kill applications (including background jobs) in order to save battery,
   * unless the app is whitelisted by the user in a custom OS setting page. This behaviour is hard to detect and not
   * standard, and does not happen on a stock android. In this case, the user has to whitelist the app.
   */
  private fun checkBackgroundWorkerCanRun(context: Context) {
    val channelsWatchOutcome = Prefs.getWatcherLastAttemptOutcome(context)
    if (channelsWatchOutcome.second > 0 && System.currentTimeMillis() - channelsWatchOutcome.second > InAppNotifications.DELAY_BEFORE_BACKGROUND_WARNING) {
      log.warn("watcher has not run since {}", DateFormat.getDateTimeInstance().format(Date(channelsWatchOutcome.second)))
      appKit.notifications.value?.add(InAppNotifications.NotificationTypes.BACKGROUND_WORKER_CANNOT_RUN)
    } else {
      appKit.notifications.value?.remove(InAppNotifications.NotificationTypes.BACKGROUND_WORKER_CANNOT_RUN)
    }
  }

  private fun checkWalletIsSecure(context: Context) {
    if (!Prefs.getIsSeedEncrypted(context)) {
      appKit.notifications.value?.add(InAppNotifications.NotificationTypes.NO_PIN_SET)
    } else {
      appKit.notifications.value?.remove(InAppNotifications.NotificationTypes.NO_PIN_SET)
    }
  }

  private fun checkMnemonics(context: Context) {
    val timestamp = Prefs.getMnemonicsSeenTimestamp(context)
    if (timestamp == 0L) {
      appKit.notifications.value?.add(InAppNotifications.NotificationTypes.MNEMONICS_NEVER_SEEN)
    } else {
      appKit.notifications.value?.remove(InAppNotifications.NotificationTypes.MNEMONICS_NEVER_SEEN)
      if (System.currentTimeMillis() - timestamp > InAppNotifications.MNEMONICS_REMINDER_INTERVAL) {
        //notifications.value?.add(NotificationTypes.MNEMONICS_REMINDER)
      } else {
        appKit.notifications.value?.remove(InAppNotifications.NotificationTypes.MNEMONICS_REMINDER)
      }
    }
  }

}
