package com.mike.pagertest;

import android.app.Fragment;
import android.app.FragmentManager;
import android.support.v13.app.FragmentPagerAdapter;

import java.util.ArrayList;
import java.util.List;

public class SamplePagerAdapter extends FragmentPagerAdapter {

    List<String> mItems = new ArrayList<>();

    public SamplePagerAdapter(FragmentManager fm) {
        super(fm);
    }

    @Override
    public Fragment getItem(int position) {
        return ImageFragment.newInstance(mItems.get(position));
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    public void setItems(List<String> list) {
        mItems = list;
        notifyDataSetChanged();
    }
}
