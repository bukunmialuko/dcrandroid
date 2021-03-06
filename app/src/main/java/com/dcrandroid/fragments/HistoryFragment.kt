/*
 * Copyright (c) 2018-2019 The Decred developers
 * Use of this source code is governed by an ISC
 * license that can be found in the LICENSE file.
 */

package com.dcrandroid.fragments

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.dcrandroid.R
import com.dcrandroid.activities.TransactionDetailsActivity
import com.dcrandroid.adapter.TransactionAdapter
import com.dcrandroid.data.Constants
import com.dcrandroid.data.Transaction
import com.dcrandroid.util.*
import com.google.gson.GsonBuilder
import kotlinx.android.synthetic.main.content_history.*
import java.util.*

class HistoryFragment : Fragment(), SwipeRefreshLayout.OnRefreshListener {

    private var latestTransactionHeight: Int = 0
    private var needsUpdate = false
    private var isForeground: Boolean = false
    private var transactionTypeSelected = ""

    private var transactionAdapter: TransactionAdapter? = null
    private var sortSpinnerAdapter: ArrayAdapter<String>? = null

    private val transactionList = ArrayList<Transaction>()
    private var fixedTransactionList: ArrayList<Transaction> = ArrayList()
    private val availableTxTypes = ArrayList<String>()

    private var walletData: WalletData? = null

    private var util: PreferenceUtil? = null

    private val ALL: String by lazy { getString(R.string.all) }
    private val SENT: String by lazy { getString(R.string.sent) }
    private val RECEIVED: String by lazy { getString(R.string.received) }
    private val YOURSELF: String by lazy { getString(R.string.yourself) }
    private val STAKING: String by lazy { getString(R.string.staking) }
    private val COINBASE: String by lazy { getString(R.string.coinbase) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.content_history, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        if (activity != null)
            activity!!.title = getString(R.string.history)

        util = PreferenceUtil(context!!)
        walletData = WalletData.getInstance()

        swipe_refresh_layout.setColorSchemeResources(
                R.color.colorPrimaryDarkBlue,
                R.color.colorPrimaryDarkBlue,
                R.color.colorPrimaryDarkBlue,
                R.color.colorPrimaryDarkBlue)
        swipe_refresh_layout.setOnRefreshListener(this)

        transactionAdapter = TransactionAdapter(transactionList, context)

        val mLayoutManager = LinearLayoutManager(context)
        history_recycler_view.layoutManager = mLayoutManager

        history_recycler_view.itemAnimator = DefaultItemAnimator()
        history_recycler_view.addItemDecoration(DividerItemDecoration(context!!, LinearLayoutManager.VERTICAL))
        history_recycler_view.addOnItemTouchListener(RecyclerTouchListener(context, history_recycler_view, object : RecyclerTouchListener.ClickListener {
            override fun onClick(view: View, position: Int) {
                if (transactionList.size <= position || position < 0) {
                    return
                }
                val history = transactionList[position]
                val i = Intent(context, TransactionDetailsActivity::class.java)
                val extras = Bundle()
                extras.putLong(Constants.AMOUNT, history.amount)
                extras.putLong(Constants.FEE, history.fee)
                extras.putLong(Constants.TIMESTAMP, history.timestamp)
                extras.putInt(Constants.HEIGHT, history.height)
                extras.putLong(Constants.TOTAL_INPUT, history.totalInput)
                extras.putLong(Constants.TOTAL_OUTPUT, history.totalOutput)
                extras.putString(Constants.TYPE, history.type)
                extras.putString(Constants.HASH, history.hash)
                extras.putString(Constants.RAW, history.raw)
                extras.putInt(Constants.DIRECTION, history.direction)
                extras.putSerializable(Constants.INPUTS, history.inputs)
                extras.putSerializable(Constants.OUTPUTS, history.outputs)
                i.putExtras(extras)
                startActivity(i)
            }

            override fun onLongClick(view: View, position: Int) {

            }
        }))

        history_recycler_view.adapter = transactionAdapter
        registerForContextMenu(history_recycler_view)

        availableTxTypes.add(ALL)

        sortSpinnerAdapter = ArrayAdapter(context!!, android.R.layout.simple_spinner_item, availableTxTypes)
        sortSpinnerAdapter!!.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerHistory.adapter = sortSpinnerAdapter

        transactionTypeSelected = ALL
        spinnerHistory.setSelection(0, false)

        setupSortListener()

        prepareHistoryData()
    }

    override fun onResume() {
        super.onResume()
        if (context != null) {
            val filter = IntentFilter(Constants.SYNCED)
            context!!.registerReceiver(receiver, filter)
        }

        isForeground = true
        if (needsUpdate) {
            needsUpdate = false
            prepareHistoryData()
        }

    }

    override fun onPause() {
        super.onPause()
        if (context != null) {
            context!!.unregisterReceiver(receiver)
        }
        isForeground = false
    }

    private fun prepareHistoryData() {
        if (!isForeground) {
            needsUpdate = true
            return
        }

        history_recycler_view.visibility = View.GONE
        no_history.visibility = View.VISIBLE
        swipe_refresh_layout.isRefreshing = true

        if (transactionList.size == 0) {
            no_history.setText(R.string.no_transactions)
            no_history.visibility = View.VISIBLE
            history_recycler_view.visibility = View.GONE
        } else {
            no_history.visibility = View.GONE
            history_recycler_view.visibility = View.VISIBLE
        }

        if (walletData!!.syncing) {
            no_history.setText(R.string.synchronizing)
            swipe_refresh_layout.isRefreshing = false
            return
        }

        object : Thread() {
            override fun run() {
                try {
                    val jsonResult = walletData!!.wallet.getTransactions(0)

                    val gson = GsonBuilder().registerTypeHierarchyAdapter(ArrayList::class.java, Deserializer.TransactionDeserializer())
                            .create()

                    val transactions = gson.fromJson(jsonResult, Array<Transaction>::class.java)

                    activity!!.runOnUiThread {
                        if (transactions.isEmpty()) {
                            no_history.setText(R.string.no_transactions_have_occurred)
                            no_history.visibility = View.VISIBLE
                            history_recycler_view.visibility = View.GONE
                            if (swipe_refresh_layout.isRefreshing) {
                                swipe_refresh_layout.isRefreshing = false
                            }
                        } else {
                            fixedTransactionList.clear()
                            fixedTransactionList.addAll(transactions)

                            val latestTx = Collections.min(fixedTransactionList, TransactionComparator.MinConfirmationSort())
                            latestTransactionHeight = latestTx.height + 1

                            if (fixedTransactionList.size > 0) {
                                val recentTransactionHash = util!!.get(Constants.RECENT_TRANSACTION_HASH)
                                if (recentTransactionHash.isNotEmpty()) {
                                    val hashIndex = fixedTransactionList.find(recentTransactionHash)

                                    if (hashIndex == -1) {
                                        // All transactions in this list is new
                                        fixedTransactionList.animateNewItems(0, fixedTransactionList.size - 1)
                                    } else if (hashIndex != 0) {
                                        fixedTransactionList.animateNewItems(0, hashIndex - 1)
                                    }
                                }

                                util!!.set(Constants.RECENT_TRANSACTION_HASH, fixedTransactionList[0].hash)
                            }

                            availableTxTypes.clear()

                            availableTxTypes.add("$ALL (${fixedTransactionList.size})")
                            availableTxTypes.add("$SENT (" + fixedTransactionList.count {
                                it.direction == 0 && it.type.equals(Constants.REGULAR, ignoreCase = true)
                            }
                                    + ")")
                            availableTxTypes.add("$RECEIVED (" + fixedTransactionList.count {
                                it.direction == 1 && it.type.equals(Constants.REGULAR, ignoreCase = true)
                            }
                                    + ")")
                            availableTxTypes.add("$YOURSELF (" + fixedTransactionList.count {
                                it.direction == 2 && it.type.equals(Constants.REGULAR, ignoreCase = true)
                            }
                                    + ")")

                            for (i in fixedTransactionList.indices) {
                                var type = fixedTransactionList[i].type
                                type = if (type.equals(Constants.VOTE, ignoreCase = true) || type.equals(Constants.TICKET_PURCHASE, ignoreCase = true)
                                        || type.equals(Constants.REVOCATION, ignoreCase = true)) {
                                    "$STAKING (" +
                                            fixedTransactionList.count {
                                                it.type.equals(Constants.VOTE, ignoreCase = true)
                                                        || it.type.equals(Constants.TICKET_PURCHASE, ignoreCase = true)
                                                        || it.type.equals(Constants.REVOCATION, ignoreCase = true)
                                            } + ")"
                                } else if (type.equals(Constants.COINBASE, ignoreCase = true)) {
                                    "$COINBASE (" + fixedTransactionList.count { it.type.equals(Constants.COINBASE, ignoreCase = true) }
                                } else {
                                    continue
                                }

                                if (!availableTxTypes.contains(type)) {
                                    availableTxTypes.add(type)
                                }

                                if (availableTxTypes.size >= 6) { // There're only 5 sort types
                                    break
                                }
                            }

                            sortSpinnerAdapter!!.notifyDataSetChanged()

                            sortTransactions()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }
        }.start()
    }

    override fun onRefresh() {
        prepareHistoryData()
    }

    private fun sortTransactions() {
        println("Sorting transaction ${fixedTransactionList.size}")
        // TODO: Simplify this using a custom sublist function and a predicate
        transactionList.clear()
        if (transactionTypeSelected.startsWith(ALL)) {
            transactionList.addAll(fixedTransactionList)
        } else {
            for (i in fixedTransactionList.indices) {
                val item = fixedTransactionList[i]
                when {
                    transactionTypeSelected.startsWith(STAKING) -> if (item.type == Constants.VOTE || item.type == Constants.REVOCATION
                            || item.type == Constants.TICKET_PURCHASE) {
                        transactionList.add(item)
                    }
                    transactionTypeSelected.startsWith(item.type) -> // Coinbase transaction
                        transactionList.add(item)
                    transactionTypeSelected.startsWith(SENT) && item.direction == 0 -> transactionList.add(item)
                    transactionTypeSelected.startsWith(RECEIVED) && item.direction == 1 -> transactionList.add(item)
                    transactionTypeSelected.startsWith(YOURSELF) && item.direction == 2 -> transactionList.add(item)
                }
            }
        }

        println("Sort Size: ${transactionList.size} Selected: $transactionTypeSelected")
        if (transactionList.size > 0) {
            history_recycler_view.visibility = View.VISIBLE
            no_history.visibility = View.GONE
        } else {
            no_history.setText(R.string.no_transactions)
            no_history.visibility = View.VISIBLE
            history_recycler_view.visibility = View.GONE
        }

        if (swipe_refresh_layout.isRefreshing) {
            swipe_refresh_layout.isRefreshing = false
        }

        transactionAdapter!!.notifyDataSetChanged()
    }

    fun newTransaction(transaction: Transaction) {
        if (transactionList.find(transaction.hash) != -1) {
            // Transaction is a duplicate
            return
        }

        latestTransactionHeight = transaction.height + 1
        fixedTransactionList.add(0, transaction)
        println("New transaction hash ${transaction.hash}")
        util!!.set(Constants.RECENT_TRANSACTION_HASH, transaction.hash)

        if ((transactionTypeSelected.startsWith("ALL", ignoreCase = true) ||
                        transactionTypeSelected.startsWith(transaction.type, ignoreCase = true) ||
                        (transactionTypeSelected.startsWith(Constants.SENT, ignoreCase = true) && transaction.direction == 0) ||
                        (transactionTypeSelected.startsWith(Constants.RECEIVED, ignoreCase = true) && transaction.direction == 1) ||
                        (transactionTypeSelected.startsWith(Constants.TOSELF, ignoreCase = true) && transaction.direction == 2) ||
                        transactionTypeSelected.startsWith(Constants.STAKING, ignoreCase = true) && (transaction.type == Constants.VOTE ||
                        transaction.type == Constants.REVOCATION ||
                        transaction.type == Constants.TICKET_PURCHASE))) {
            transaction.animate = true
            transactionList.add(0, transaction)
            history_recycler_view.post {
                history_recycler_view.visibility = View.VISIBLE
                transactionAdapter!!.notifyDataSetChanged()
            }
        }
    }

    fun transactionConfirmed(hash: String, height: Int) {
        for (i in transactionList.indices) {
            if (transactionList[i].hash == hash) {
                val transaction = transactionList[i]
                transaction.height = height
                transaction.animate = false
                latestTransactionHeight = transaction.height + 1
                transactionList[i] = transaction
                activity!!.runOnUiThread { transactionAdapter!!.notifyDataSetChanged() }
                break
            }
        }
    }

    fun blockAttached(height: Int) {
        if (height - latestTransactionHeight < 2) {
            for (i in transactionList.indices) {
                val tx = transactionList[i]
                if (height - tx.height >= 2) {
                    continue
                }
                tx.animate = false
                if (activity == null) {
                    return
                }
                activity!!.runOnUiThread { transactionAdapter!!.notifyItemChanged(i) }
            }
        }
    }

    private fun setupSortListener() {
        spinnerHistory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View, position: Int, id: Long) {
                println("onItemSelected")
                transactionTypeSelected = availableTxTypes[position]
                sortTransactions()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {

            }
        }
    }

    private var receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != null && intent.action == Constants.SYNCED) {
                if (!walletData!!.syncing) {
                    prepareHistoryData()
                }
            }
        }
    }

    private fun ArrayList<Transaction>.find(hash: String): Int {
        for (i in this.indices) {
            val item = this[i]
            if (item.hash == hash) {
                return i
            }
        }
        return -1
    }

    private fun ArrayList<Transaction>.animateNewItems(start: Int, count: Int) {
        for (i: Int in start..count) {
            val item = this[i]
            item.animate = true
        }
    }
}