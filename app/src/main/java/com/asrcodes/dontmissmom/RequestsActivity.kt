package com.asrcodes.dontmissmom

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class RequestsViewModel : ViewModel() {
    val repository = AppDependencies.repository()
    val items = MutableLiveData<List<RequestItem>>(emptyList())
    val loading = MutableLiveData(true)
    val error = MutableLiveData(false)
    val busy = MutableLiveData<Set<String>>(emptySet())
    val result = MutableLiveData<Pair<Int, Throwable?>?>()
    private var subscription: Subscription? = null
    init { reload() }
    fun reload() {
        subscription?.remove()
        loading.value = true; error.value = false
        subscription = repository.observeRequests { response ->
            loading.value = false
            error.value = response.isFailure
            response.getOrNull()?.let { items.value = it }
        }
    }
    fun act(request: RequestItem, action: String, reason: String = "") {
        if (request.id in busy.value.orEmpty()) return
        busy.value = busy.value.orEmpty() + request.id
        val callback: (Result<Unit>) -> Unit = { response ->
            busy.value = busy.value.orEmpty() - request.id
            result.value = Pair(when(action) { "accept" -> R.string.request_accepted; "report" -> R.string.report_sent; else -> R.string.request_declined }, response.exceptionOrNull())
        }
        when(action) {
            "accept" -> repository.acceptRequest(request, callback)
            "report" -> repository.reportRequest(request, reason, callback)
            else -> repository.rejectRequest(request, callback)
        }
    }
    override fun onCleared() { subscription?.remove() }
}

class RequestsActivity : BaseActivity() {
    private lateinit var model: RequestsViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_requests)
        model = ViewModelProvider(this)[RequestsViewModel::class.java]
        if (model.repository.currentUid == null) { finish(); return }
        val adapter = RequestsAdapter({ act(it, "accept") }, { act(it, "decline") }, { report(it) })
        findViewById<RecyclerView>(R.id.recyclerRequests).apply {
            layoutManager = LinearLayoutManager(this@RequestsActivity)
            this.adapter = adapter
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View>(R.id.btnRetryRequests).setOnClickListener { model.reload() }
        var requestToFocus = savedInstanceState?.getString("requestToFocus") ?: intent.getStringExtra("requestId")
        model.items.observe(this) { items ->
            adapter.submitList(items) {
                val id = requestToFocus
                if (id != null && model.loading.value != true && model.error.value != true) {
                    val position = items.indexOfFirst { it.id == id }
                    if (position >= 0) findViewById<RecyclerView>(R.id.recyclerRequests).scrollToPosition(position)
                    else message(R.string.request_no_longer_pending)
                    requestToFocus = null
                }
            }
            render()
        }
        model.loading.observe(this) { render() }
        model.error.observe(this) { render() }
        model.busy.observe(this) { adapter.busyIds = it }
        model.result.observe(this) { result ->
            if (result != null) {
                model.result.value = null
                if (result.second == null) message(result.first) else failure(result.second)
            }
        }
    }
    private fun act(request: RequestItem, action: String) {
        if (!isOnline()) message(R.string.connection_error) else model.act(request, action)
    }
    private fun render() {
        val loading = model.loading.value == true
        val error = model.error.value == true
        findViewById<View>(R.id.requestsProgress).show(loading)
        findViewById<View>(R.id.requestsState).show(!loading && (error || model.items.value.isNullOrEmpty()))
        findViewById<View>(R.id.btnRetryRequests).show(error)
        findViewById<TextView>(R.id.tvRequestsStateTitle).setText(if (error) R.string.connection_error else R.string.empty_requests_title)
        findViewById<TextView>(R.id.tvRequestsStateBody).setText(if (error) R.string.operation_error else R.string.empty_requests_body)
    }
    private fun report(request: RequestItem) {
        val view = layoutInflater.inflate(R.layout.dialog_input, null)
        view.findViewById<TextView>(R.id.dialogSubtitle).setText(R.string.report_hint)
        val layout = view.findViewById<TextInputLayout>(R.id.inputLayout).apply { hint = getString(R.string.report_hint) }
        val input = view.findViewById<TextInputEditText>(R.id.dialogEditText).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.report_title).setView(view)
            .setPositiveButton(R.string.report, null).setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val reason = input.text.toString().trim()
                if (reason.isEmpty()) layout.error = getString(R.string.report_empty)
                else if (!isOnline()) layout.error = getString(R.string.connection_error)
                else { model.act(request, "report", reason); dialog.dismiss() }
            }
        }
        dialog.show()
    }
}
