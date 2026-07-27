package com.example.ble_heartrate

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView

/**
 * List adapter for the configured threshold rules. Every row shows the rule
 * description plus a toggle that temporarily disables the rule and a delete button.
 */
class RuleAdapter(
    private val context: Context,
    private val onToggle: (HeartRateService.AlertRule) -> Unit,
    private val onDelete: (HeartRateService.AlertRule) -> Unit
) : BaseAdapter() {

    private val rules = mutableListOf<HeartRateService.AlertRule>()

    fun submit(newRules: List<HeartRateService.AlertRule>) {
        rules.clear()
        rules.addAll(newRules)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = rules.size

    override fun getItem(position: Int): HeartRateService.AlertRule = rules[position]

    override fun getItemId(position: Int): Long = rules[position].bpm.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.item_rule, parent, false)
        val rule = rules[position]

        val tvRule = view.findViewById<TextView>(R.id.tvRule)
        val btnToggle = view.findViewById<Button>(R.id.btnRuleToggle)
        val btnDelete = view.findViewById<Button>(R.id.btnRuleDelete)

        tvRule.text = context.getString(R.string.rule_item, rule.bpm, rule.periodMs)
        tvRule.setTextColor(if (rule.enabled) Color.parseColor("#424242") else Color.parseColor("#BDBDBD"))
        btnToggle.setText(if (rule.enabled) R.string.rule_disable else R.string.rule_enable)

        btnToggle.setOnClickListener { onToggle(rule) }
        btnDelete.setOnClickListener { onDelete(rule) }

        return view
    }
}
