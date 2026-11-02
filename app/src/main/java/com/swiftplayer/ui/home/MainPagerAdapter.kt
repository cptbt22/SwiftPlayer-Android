package com.swiftplayer.ui.home

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.swiftplayer.ui.home.fragments.EmbyFragment
import com.swiftplayer.ui.home.fragments.LocalMediaFragment
import com.swiftplayer.ui.home.fragments.SmbBrowserFragment

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    private var localFragment: LocalMediaFragment? = null
    private var smbFragment: SmbBrowserFragment? = null
    private var embyFragment: EmbyFragment? = null

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> LocalMediaFragment().also { localFragment = it }
            1 -> SmbBrowserFragment().also { smbFragment = it }
            2 -> EmbyFragment().also { embyFragment = it }
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }

    fun getLocalFragment(): LocalMediaFragment? = localFragment

    fun getSmbFragment(): SmbBrowserFragment? = smbFragment

    fun getEmbyFragment(): EmbyFragment? = embyFragment

    fun refreshAll() {
        localFragment?.refresh()
        smbFragment?.refresh()
    }
}
