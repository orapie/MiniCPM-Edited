package com.example.minicpm_v_demo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/** 将模型目录绑定到管理页列表，并按模型 ID 更新当前选中样式。 */
class ModelAdapter(
    private val models: List<ModelInfo>,
    private val selectedModelId: String,
    private val onModelSelected: (ModelInfo) -> Unit
) : RecyclerView.Adapter<ModelAdapter.ViewHolder>() {

    var canSelect: () -> Boolean = { true }

    private var currentSelectedId = selectedModelId
    private var installedModelIds = emptySet<String>()

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tv_model_name)
        val tvInstalled: TextView = itemView.findViewById(R.id.tv_model_installed)
        val tvDesc: TextView = itemView.findViewById(R.id.tv_model_desc)
        val tvSelected: TextView = itemView.findViewById(R.id.iv_selected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_model_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]
        holder.tvName.text = model.displayName
        holder.tvInstalled.isVisible = model.id in installedModelIds
        holder.tvDesc.text = model.getDescription(holder.itemView.context)

        val isSelected = model.id == currentSelectedId
        holder.tvSelected.text = if (isSelected) "●" else "○"
        holder.tvSelected.alpha = if (isSelected) 1.0f else 0.4f

        holder.itemView.setOnClickListener {
            if (canSelect() && model.id != currentSelectedId) {
                val oldIndex = models.indexOfFirst { it.id == currentSelectedId }
                currentSelectedId = model.id
                if (oldIndex >= 0) notifyItemChanged(oldIndex)
                notifyItemChanged(position)
                onModelSelected(model)
            }
        }
    }

    override fun getItemCount(): Int = models.size

    fun updateInstalledModels(modelIds: Set<String>) {
        val previous = installedModelIds
        if (previous == modelIds) return
        installedModelIds = modelIds
        models.forEachIndexed { index, model ->
            if ((model.id in previous) != (model.id in modelIds)) notifyItemChanged(index)
        }
    }

    fun updateSelection(modelId: String) {
        val oldIndex = models.indexOfFirst { it.id == currentSelectedId }
        currentSelectedId = modelId
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        val newIndex = models.indexOfFirst { it.id == modelId }
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }
}
