package com.skydroid.insulatordetect.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.skydroid.insulatordetect.R
import com.skydroid.insulatordetect.detect.Piece

/**
 * 左侧绝缘子串列表适配器（对应 HTML renderList/pieceCell）：
 * 单联为线性列表（含序号列）；双联配合 GridLayoutManager(3列) 按
 * [行序号 | 左列片 | 右列片] 逐行渲染
 */
class PieceAdapter(
    private val pieces: List<Piece>,
    private var dual: Boolean,
    private val onItemClick: (index: Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int {
        return if (dual) {
            val rows = (pieces.size + 1) / 2
            rows * 3
        } else {
            pieces.size
        }
    }

    override fun getItemViewType(position: Int): Int {
        if (!dual) return TYPE_SINGLE
        return if (position % 3 == 0) TYPE_ROW_INDEX else TYPE_DUAL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ROW_INDEX -> RowIndexVH(inflater.inflate(R.layout.item_row_index, parent, false) as TextView)
            TYPE_DUAL -> DualVH(inflater.inflate(R.layout.item_piece_dual, parent, false))
            else -> SingleVH(inflater.inflate(R.layout.item_piece_single, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is RowIndexVH -> {
                val row = position / 3
                holder.tv.text = if (row * 2 < pieces.size) pieces[row * 2].row.toString() else ""
            }
            is DualVH -> {
                // 每行 3 格：col1 左列片、col2 右列片
                val col = position % 3
                val index = (position / 3) * 2 + (col - 1)
                if (index < pieces.size) {
                    holder.root.visibility = View.VISIBLE
                    bindPiece(holder.root, holder.icon, holder.value, holder.state, null, index)
                } else {
                    holder.root.visibility = View.INVISIBLE
                }
            }
            is SingleVH -> {
                bindPiece(holder.root, holder.icon, holder.value, holder.state, holder.idx, position)
            }
        }
    }

    private fun bindPiece(
        root: View, icon: InsulatorIconView, tvValue: TextView, tvState: TextView,
        tvIdx: TextView?, index: Int
    ) {
        val p = pieces[index]
        icon.state = p.state
        // 状态背景（对应 .piece[data-s]）：0透明 / 1蓝 / 2绿边 / 3红
        val bgRes = when (p.state) {
            Piece.STATE_MEASURING -> R.drawable.bg_piece_measuring
            Piece.STATE_OK -> R.drawable.bg_piece_ok
            Piece.STATE_BAD -> R.drawable.bg_piece_bad
            else -> R.drawable.bg_piece_idle
        }
        if (root.tag != bgRes) {
            root.tag = bgRes
            root.setBackgroundResource(bgRes)
        }
        tvValue.text = when {
            p.state == Piece.STATE_MEASURING -> "检测中…"
            p.value != null -> "${p.value} MΩ"
            else -> "— MΩ"
        }
        tvState.text = when (p.state) {
            Piece.STATE_MEASURING -> "冲击采集中"
            Piece.STATE_OK -> "合格"
            Piece.STATE_BAD -> "异常"
            else -> "待检测"
        }
        val color = when (p.state) {
            Piece.STATE_MEASURING -> 0xFF4DA3FF.toInt()
            Piece.STATE_OK -> 0xFF3DDC84.toInt()
            Piece.STATE_BAD -> 0xFFFF3B30.toInt()
            else -> 0xFFCFE4D8.toInt()
        }
        tvValue.setTextColor(color)
        tvIdx?.let {
            it.text = p.label
            it.setTextColor(
                when (p.state) {
                    Piece.STATE_MEASURING -> 0xFF4DA3FF.toInt()
                    Piece.STATE_BAD -> 0xFFFF3B30.toInt()
                    else -> 0xFF9FB3A8.toInt()
                }
            )
        }
        root.setOnClickListener { onItemClick(index) }
    }

    fun setDualMode(dual: Boolean) {
        if (this.dual != dual) {
            this.dual = dual
            notifyDataSetChanged()
        }
    }

    private class RowIndexVH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    private class DualVH(root: View) : RecyclerView.ViewHolder(root) {
        val root: View = root
        val icon: InsulatorIconView = root.findViewById(R.id.iconInsulator)
        val value: TextView = root.findViewById(R.id.tvValue)
        val state: TextView = root.findViewById(R.id.tvState)
    }

    private class SingleVH(root: View) : RecyclerView.ViewHolder(root) {
        val root: View = root
        val idx: TextView = root.findViewById(R.id.tvIdx)
        val icon: InsulatorIconView = root.findViewById(R.id.iconInsulator)
        val value: TextView = root.findViewById(R.id.tvValue)
        val state: TextView = root.findViewById(R.id.tvState)
    }

    companion object {
        private const val TYPE_SINGLE = 0
        private const val TYPE_DUAL = 1
        private const val TYPE_ROW_INDEX = 2
    }
}
