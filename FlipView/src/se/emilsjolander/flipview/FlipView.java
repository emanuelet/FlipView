package se.emilsjolander.flipview;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import androidx.annotation.NonNull;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.Scroller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import se.emilsjolander.flipview.Recycler.Scrap;

public class FlipView extends FrameLayout {

    public interface OnFlipListener {
        public void onFlippedToPage(FlipView v, int position, long id);
    }

    public interface OnDistanceListener {
        public void onDistanceChange(float distance);
    }

    public interface OnOverFlipListener {
        public void onOverFlip(FlipView v, OverFlipMode mode,
                               boolean overFlippingPrevious, float overFlipDistance,
                               float flipDistancePerPage);
    }

    /**
     * @author emilsjolander
     * <p>
     * Class to hold a view and its corresponding info
     */
    static class Page {
        View v;
        int position;
        int viewType;
        boolean valid;
    }

    /**
     * Class to hold a view during cascade drawing, also stores the order of drawing
     *
     * @author barannikov_mikhail
     */
    static class CascadeViewHolder implements Comparable<CascadeViewHolder> {
        View v;
        int drawingOrder;
        float degreesFlipped;

        @Override
        public int compareTo(@NonNull CascadeViewHolder another) {
            final Integer o = drawingOrder;
            return o.compareTo(another.drawingOrder);
        }
    }

    // animation property for ObjectAnimator
    public static final String FLIP_DISTANCE = "flipDistance";

    // epsilon for float comparison
    private static final float EPSILON = 0.1f;
    // this will be the postion when there is not data
    private static final int INVALID_PAGE_POSITION = -1;
    // "null" flip distance
    private static final int INVALID_FLIP_DISTANCE = -1;

    private static final int PEAK_ANIM_DURATION = 600;// in ms

    // for normalizing width/height
    private static final int FLIP_DISTANCE_PER_PAGE = 180;
    private static final int MAX_SHADOW_ALPHA = 127;// out of 255
    private static final int MAX_GRADIENT_ALPHA = 255;// out of 255
    private static final int MAX_SHADE_ALPHA = 130;// out of 255
    private static final int MAX_SHINE_ALPHA = 100;// out of 255

    // value for no pointer
    private static final int INVALID_POINTER = -1;

    // constant used by the attributes
    private static final int VERTICAL_FLIP = 0;

    // constant used by the attributes
    @SuppressWarnings("unused")
    private static final int HORIZONTAL_FLIP = 1;

    private DataSetObserver dataSetObserver = new DataSetObserver() {

        @Override
        public void onChanged() {
            dataSetChanged();
        }

        @Override
        public void onInvalidated() {
            dataSetInvalidated();
        }

    };

    private Scroller mScroller;
    private final Interpolator flipInterpolator = new DecelerateInterpolator();
    private ValueAnimator mPeakAnim;
    private TimeInterpolator mPeakInterpolator = new AccelerateDecelerateInterpolator();

    private boolean mIsFlippingVertically = true;
    private boolean mIsFlipping;
    private boolean mIsUnableToFlip;
    private boolean mIsFlippingEnabled = true;
    private boolean mLastTouchAllowed = true;
    private int mTouchSlop;
    private boolean mIsOverFlipping;

    // cascade flipping
    private boolean mIsFlippingCascade = false;
    private boolean mCascadeBitmapsReady = false;
    private int mCascadeOffset = 30;
    private int mMaxSinglePageFlipAnimDuration = 360; // in ms
    private int mCascadeFlipDuration = 1000;
    private int mCascadeEndFlipDistance = -1;
    private List<View> mCascadeViews = new ArrayList<>();
    private boolean mIsCascadeAnimationPrepared = true;

    // distance listener
    private OnDistanceListener mOnDistanceListener;
    private boolean mIsFlippingToDistance = false;

    // api 18
    private Bitmap mBitmap;
    private Canvas mCanvas;
    private Bitmap mBitmapR;
    private Canvas mCanvasR;

    // scroll speed multiplier
    private float mSpeedMultiplier = 1f;

    // keep track of pointer
    private float mLastX = -1;
    private float mLastY = -1;
    private int mActivePointerId = INVALID_POINTER;

    // velocity stuff
    private VelocityTracker mVelocityTracker;
    private int mMinimumVelocity;
    private int mMaximumVelocity;

    // views get recycled after they have been pushed out of the active queue
    private Recycler mRecycler = new Recycler();

    private ListAdapter mAdapter;
    private int mPageCount = 0;
    private Page mPreviousPage = new Page();
    private Page mCurrentPage = new Page();
    private Page mNextPage = new Page();
    private View mEmptyView;

    private OnFlipListener mOnFlipListener;
    private OnOverFlipListener mOnOverFlipListener;

    private float mFlipDistance = INVALID_FLIP_DISTANCE;
    private int mCurrentPageIndex = INVALID_PAGE_POSITION;
    private int mLastDispatchedPageEventIndex = 0;
    private long mCurrentPageId = 0;

    private OverFlipMode mOverFlipMode;
    private OverFlipper mOverFlipper;

    // clipping rects
    private Rect mTopRect = new Rect();
    private Rect mBottomRect = new Rect();
    private Rect mRightRect = new Rect();
    private Rect mLeftRect = new Rect();

    // used for transforming the canvas
    private Camera mCamera = new Camera();
    private Matrix mMatrix = new Matrix();

    // paints drawn above views when flipping
    private Paint mShadowPaint = new Paint();
    private Paint mShadePaint = new Paint();
    private Paint mShinePaint = new Paint();
    private Paint mGradientPaint = new Paint();
    private Paint mFlippedGradientPaint = new Paint();
    private boolean mDrawShadows = true;
    private boolean mDrawShadesAndShines = true;
    private boolean mDrawGradient = true;
    private boolean mDrawOverFlip = true;

    // flip part gradient colors
    private int mGradientColor0 = Color.BLACK;
    private int mGradientColor1 = Color.WHITE;

    public FlipView(Context context) {
        this(context, null);
    }

    public FlipView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FlipView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.FlipView);

        // 0 is vertical, 1 is horizontal
        mIsFlippingVertically = a.getInt(R.styleable.FlipView_orientation,
                VERTICAL_FLIP) == VERTICAL_FLIP;

        setOverFlipMode(OverFlipMode.values()[a.getInt(
                R.styleable.FlipView_overFlipMode, 0)]);

        a.recycle();

        init(context);
    }

    private void init(Context context) {
        final ViewConfiguration configuration = ViewConfiguration.get(context);

        mScroller = new Scroller(context, flipInterpolator);
        mTouchSlop = configuration.getScaledPagingTouchSlop();
        mMinimumVelocity = configuration.getScaledMinimumFlingVelocity();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();

        mShadowPaint.setColor(Color.BLACK);
        mShadowPaint.setStyle(Style.FILL);
        mShadePaint.setColor(Color.BLACK);
        mShadePaint.setStyle(Style.FILL);
        mShinePaint.setColor(Color.WHITE);
        mShinePaint.setStyle(Style.FILL);
        mGradientPaint.setDither(true);
    }

    private void dataSetChanged() {
        final int currentPage = mCurrentPageIndex;
        mLastDispatchedPageEventIndex = -1;
        int newPosition = currentPage;

        // if the adapter has stable ids, try to keep the page currently on
        // stable.
        if (mAdapter.hasStableIds() && currentPage != INVALID_PAGE_POSITION) {
            newPosition = getNewPositionOfCurrentPage();
        } else if (currentPage == INVALID_PAGE_POSITION) {
            newPosition = 0;
        }

        // clear cascade views
        removeAllViews();
        mCascadeViews.clear();
        mIsCascadeAnimationPrepared = false;

        // remove all the current views
        recycleActiveViews();
        mRecycler.setViewTypeCount(mAdapter.getViewTypeCount());
        mRecycler.invalidateScraps();

        mPageCount = mAdapter.getCount();

        // put the current page within the new adapter range
        newPosition = Math.min(mPageCount - 1,
                newPosition == INVALID_PAGE_POSITION ? 0 : newPosition);

        if (newPosition != INVALID_PAGE_POSITION) {
            // TODO pretty confusing
            // this will be correctly set in setFlipDistance method
            mCurrentPageIndex = INVALID_PAGE_POSITION;
            mFlipDistance = INVALID_FLIP_DISTANCE;
            flipTo(newPosition);
        } else {
            mFlipDistance = INVALID_FLIP_DISTANCE;
            mPageCount = 0;
            setFlipDistance(0, true);
        }

        updateEmptyStatus();
    }

    private int getNewPositionOfCurrentPage() {
        // check if id is on same position, this is because it will
        // often be that and this way you do not need to iterate the whole
        // dataset. If it is the same position, you are done.
        if (mCurrentPageId == mAdapter.getItemId(mCurrentPageIndex)) {
            return mCurrentPageIndex;
        }

        // iterate the dataset and look for the correct id. If it
        // exists, set that position as the current position.
        for (int i = 0; i < mAdapter.getCount(); i++) {
            if (mCurrentPageId == mAdapter.getItemId(i)) {
                return i;
            }
        }

        // Id no longer is dataset, keep current page
        return mCurrentPageIndex;
    }

    private void dataSetInvalidated() {
        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(dataSetObserver);
            mAdapter = null;
        }
        mRecycler = new Recycler();
        removeAllViews();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(0, widthMeasureSpec);
        int height = getDefaultSize(0, heightMeasureSpec);

        measureChildren(widthMeasureSpec, heightMeasureSpec);

        setMeasuredDimension(width, height);
    }

    @Override
    protected void measureChildren(int widthMeasureSpec, int heightMeasureSpec) {
        int width = getDefaultSize(0, widthMeasureSpec);
        int height = getDefaultSize(0, heightMeasureSpec);

        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(width,
                MeasureSpec.EXACTLY);
        int childHeightMeasureSpec = MeasureSpec.makeMeasureSpec(height,
                MeasureSpec.EXACTLY);
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            measureChild(child, childWidthMeasureSpec, childHeightMeasureSpec);
        }
    }

    @Override
    protected void measureChild(View child, int parentWidthMeasureSpec,
                                int parentHeightMeasureSpec) {
        child.measure(parentWidthMeasureSpec, parentHeightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutChildren();

        final int width = getWidth();
        final int height = getHeight();

        mTopRect.top = 0;
        mTopRect.left = 0;
        mTopRect.right = width;
        mTopRect.bottom = height / 2;

        mBottomRect.top = height / 2;
        mBottomRect.left = 0;
        mBottomRect.right = width;
        mBottomRect.bottom = height;

        mLeftRect.top = 0;
        mLeftRect.left = 0;
        mLeftRect.right = width / 2;
        mLeftRect.bottom = height;

        mRightRect.top = 0;
        mRightRect.left = width / 2;
        mRightRect.right = width;
        mRightRect.bottom = height;

        if (mBitmap == null && width > 0 && height > 0) {
            mBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            mCanvas = new Canvas(mBitmap);
            mBitmapR = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            mCanvasR = new Canvas(mBitmapR);
        }

        // prepare bitmaps for first and last views (API 18)
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN_MR2 &&
                mIsFlippingCascade && !mCascadeViews.isEmpty() && !mCascadeBitmapsReady &&
                width > 0 && height > 0) {
            mCascadeBitmapsReady = true;
            mCascadeViews.get(mCascadeViews.size() - 1).draw(mCanvas);
            mCascadeViews.get(0).draw(mCanvasR);
        }

        initializeGradient();
    }

    private void layoutChildren() {
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            layoutChild(child);
        }
    }

    private void layoutChild(View child) {
        child.layout(0, 0, getWidth(), getHeight());
    }

    private void setCascadeFlipDistance(float flipDistance) {

        if (mPageCount < 1) {
            mFlipDistance = 0;
            mCurrentPageIndex = INVALID_PAGE_POSITION;
            mCurrentPageId = -1;
            removeAllViews();
            mCascadeViews.clear();
            mIsCascadeAnimationPrepared = false;
            return;
        }

        if (flipDistance == mCascadeEndFlipDistance) {
            mFlipDistance = mCascadeEndFlipDistance;
            endScroll();
            return;
        }

        mFlipDistance = flipDistance;
    }

    private void setFlipDistance(float flipDistance, boolean reDraw) {

        if (mPageCount < 1) {
            mFlipDistance = 0;
            mCurrentPageIndex = INVALID_PAGE_POSITION;
            mCurrentPageId = -1;
            recycleActiveViews();
            return;
        }

        if (flipDistance == mCascadeEndFlipDistance) {
            mFlipDistance = mCascadeEndFlipDistance;
            endScroll();
            if (mOnDistanceListener != null) {
                postDistancePassed();
            }
            if (reDraw) {
                invalidate();
            }
            return;
        }

        mFlipDistance = flipDistance;

        final int currentPageIndex = Math.round(mFlipDistance / FLIP_DISTANCE_PER_PAGE);

        if (mCurrentPageIndex != currentPageIndex) {
            mCurrentPageIndex = currentPageIndex;
            mCurrentPageId = mAdapter.getItemId(mCurrentPageIndex);

            // TODO be smarter about this. Dont remove a view that will be added
            // again on the next line.
            recycleActiveViews();

            // add the new active views
            if (mCurrentPageIndex > 0) {
                fillPageForIndex(mPreviousPage, mCurrentPageIndex - 1);
                addView(mPreviousPage.v);
            }
            if (mCurrentPageIndex >= 0 && mCurrentPageIndex < mPageCount) {
                fillPageForIndex(mCurrentPage, mCurrentPageIndex);
                addView(mCurrentPage.v);
            }
            if (mCurrentPageIndex < mPageCount - 1) {
                fillPageForIndex(mNextPage, mCurrentPageIndex + 1);
                addView(mNextPage.v);
            }
        }

        if (mOnDistanceListener != null) {
            postDistancePassed();
        }

        if (reDraw) {
            invalidate();
        }
    }

    private void fillPageForIndex(Page p, int i) {
        p.position = i;
        p.viewType = mAdapter.getItemViewType(p.position);
        p.v = getView(p.position, p.viewType);
        p.valid = true;
    }

    private void recycleActiveViews() {
        // remove and recycle the currently active views
        if (mPreviousPage.valid) {
            removeView(mPreviousPage.v);
            mRecycler.addScrapView(mPreviousPage.v, mPreviousPage.position,
                    mPreviousPage.viewType);
            mPreviousPage.valid = false;
        }
        if (mCurrentPage.valid) {
            removeView(mCurrentPage.v);
            mRecycler.addScrapView(mCurrentPage.v, mCurrentPage.position,
                    mCurrentPage.viewType);
            mCurrentPage.valid = false;
        }
        if (mNextPage.valid) {
            removeView(mNextPage.v);
            mRecycler.addScrapView(mNextPage.v, mNextPage.position,
                    mNextPage.viewType);
            mNextPage.valid = false;
        }
    }

    private View getView(int index, int viewType) {
        // get the scrap from the recycler corresponding to the correct view
        // type
        Scrap scrap = mRecycler.getScrapView(index, viewType);

        // get a view from the adapter if a scrap was not found or it is
        // invalid.
        View v = null;
        if (scrap == null || !scrap.valid) {
            v = mAdapter.getView(index, scrap == null ? null : scrap.v, this);
        } else {
            v = scrap.v;
        }

        // return view
        return v;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mBitmap != null) {
            mBitmap.recycle();
        }
        mCanvas = null;
        mBitmap = null;

        if (mBitmapR != null) {
            mBitmapR.recycle();
        }
        mCanvasR = null;
        mBitmapR = null;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        mCascadeEndFlipDistance = INVALID_FLIP_DISTANCE;

        if (!mIsFlippingEnabled) {
            return false;
        }

        if (mPageCount < 1) {
            return false;
        }

        final int action = ev.getAction() & MotionEvent.ACTION_MASK;

        if (action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_UP) {
            mIsFlipping = false;
            mIsUnableToFlip = false;
            mActivePointerId = INVALID_POINTER;
            if (mVelocityTracker != null) {
                mVelocityTracker.recycle();
                mVelocityTracker = null;
            }
            return false;
        }

        if (action != MotionEvent.ACTION_DOWN) {
            if (mIsFlipping) {
                return true;
            } else if (mIsUnableToFlip) {
                return false;
            }
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                final int activePointerId = mActivePointerId;
                if (activePointerId == INVALID_POINTER) {
                    break;
                }

                final int pointerIndex = ev.findPointerIndex(
                        activePointerId);
                if (pointerIndex == -1) {
                    mActivePointerId = INVALID_POINTER;
                    break;
                }

                final float x = ev.getX(pointerIndex);
                final float dx = x - mLastX;
                final float xDiff = Math.abs(dx);
                final float y = ev.getY(pointerIndex);
                final float dy = y - mLastY;
                final float yDiff = Math.abs(dy);

                if ((mIsFlippingVertically && yDiff > mTouchSlop && yDiff > xDiff)
                        || (!mIsFlippingVertically && xDiff > mTouchSlop && xDiff > yDiff)) {
                    if (isFlippingVertically()) {
                        mSpeedMultiplier = (Math.abs(mLastY - (dy < 0 ? getTop() : getBottom()))) / getHeight();
                    } else {
                        mSpeedMultiplier = (Math.abs(mLastX - (dx < 0 ? getLeft() : getRight()))) / getWidth();
                    }
                    mIsFlipping = true;
                    mLastX = x;
                    mLastY = y;
                } else if ((mIsFlippingVertically && xDiff > mTouchSlop)
                        || (!mIsFlippingVertically && yDiff > mTouchSlop)) {
                    mIsUnableToFlip = true;
                }
                break;

            case MotionEvent.ACTION_DOWN:
                mActivePointerId = ev.getAction()
                        & MotionEvent.ACTION_POINTER_INDEX_MASK;
                mLastX = ev.getX(mActivePointerId);
                mLastY = ev.getY(mActivePointerId);
                mSpeedMultiplier = 0.5f;
                mIsFlipping = !mScroller.isFinished() | mPeakAnim != null;
                mIsUnableToFlip = false;
                mLastTouchAllowed = true;

                break;
            case MotionEvent.ACTION_POINTER_UP:
                onSecondaryPointerUp(ev);
                break;
        }

        if (!mIsFlipping) {
            trackVelocity(ev);
        }

        return mIsFlipping;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        mCascadeEndFlipDistance = INVALID_FLIP_DISTANCE;

        if (!mIsFlippingCascade) {

            if (!mIsFlippingEnabled) {
                return false;
            }

            if (mPageCount < 1) {
                return false;
            }

            if (!mIsFlipping && !mLastTouchAllowed) {
                return false;
            }

            final int action = ev.getAction();

            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_OUTSIDE) {
                mLastTouchAllowed = false;
            } else {
                mLastTouchAllowed = true;
            }

            trackVelocity(ev);

            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_DOWN:

                    // start flipping immediately if interrupting some sort of animation
                    if (endScroll() || endPeak()) {
                        mIsFlipping = true;
                    }

                    // Remember where the motion event started
                    mLastX = ev.getX();
                    mLastY = ev.getY();
                    mActivePointerId = ev.getPointerId(0);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!mIsFlipping) {
                        final int pointerIndex = ev.findPointerIndex(
                                mActivePointerId);
                        if (pointerIndex == -1) {
                            mActivePointerId = INVALID_POINTER;
                            break;
                        }
                        final float x = ev.getX(pointerIndex);
                        final float xDiff = Math.abs(x - mLastX);
                        final float y = ev.getY(pointerIndex);
                        final float yDiff = Math.abs(y - mLastY);
                        if ((mIsFlippingVertically && yDiff > mTouchSlop && yDiff > xDiff)
                                || (!mIsFlippingVertically && xDiff > mTouchSlop && xDiff > yDiff)) {
                            mIsFlipping = true;
                            mLastX = x;
                            mLastY = y;
                        }
                    }
                    if (mIsFlipping) {
                        // Scroll to follow the motion event
                        final int activePointerIndex = ev
                                .findPointerIndex(mActivePointerId);
                        if (activePointerIndex == -1) {
                            mActivePointerId = INVALID_POINTER;
                            break;
                        }
                        final float x = ev.getX(activePointerIndex);
                        final float deltaX = mLastX - x;
                        final float y = ev.getY(activePointerIndex);
                        final float deltaY = mLastY - y;
                        mLastX = x;
                        mLastY = y;

                        float deltaFlipDistance;
                        if (mIsFlippingVertically) {
                            deltaFlipDistance = deltaY;
                        } else {
                            deltaFlipDistance = deltaX;
                        }

                        deltaFlipDistance /= ((isFlippingVertically() ? getHeight()
                                : getWidth()) / FLIP_DISTANCE_PER_PAGE) * mSpeedMultiplier;
                        float newFlipDistance = mFlipDistance + deltaFlipDistance;

                        // check for max possible distance
                        if (newFlipDistance > mPageCount * FLIP_DISTANCE_PER_PAGE + FLIP_DISTANCE_PER_PAGE / 2 - 1) {
                            newFlipDistance = mPageCount * FLIP_DISTANCE_PER_PAGE + FLIP_DISTANCE_PER_PAGE / 2 - 1;
                        }

                        setFlipDistance(newFlipDistance < -FLIP_DISTANCE_PER_PAGE ? -FLIP_DISTANCE_PER_PAGE : newFlipDistance, true);

                        final int minFlipDistance = 0;
                        final int maxFlipDistance = (mPageCount - 1)
                                * FLIP_DISTANCE_PER_PAGE;
                        final boolean isOverFlipping = mFlipDistance < minFlipDistance
                                || mFlipDistance > maxFlipDistance;
                        if (isOverFlipping) {
                            mIsOverFlipping = true;
                            setFlipDistance(mOverFlipper.calculate(mFlipDistance,
                                    minFlipDistance, maxFlipDistance), true);
                            if (mOnOverFlipListener != null) {
                                float overFlip = mOverFlipper.getTotalOverFlip();
                                mOnOverFlipListener.onOverFlip(this, mOverFlipMode,
                                        overFlip < 0, Math.abs(overFlip),
                                        FLIP_DISTANCE_PER_PAGE);
                            }
                        } else if (mIsOverFlipping) {
                            mIsOverFlipping = false;
                            if (mOnOverFlipListener != null) {
                                // TODO in the future should only notify flip distance 0
                                // on the correct edge (previous/next)
                                mOnOverFlipListener.onOverFlip(this, mOverFlipMode,
                                        false, 0, FLIP_DISTANCE_PER_PAGE);
                                mOnOverFlipListener.onOverFlip(this, mOverFlipMode,
                                        true, 0, FLIP_DISTANCE_PER_PAGE);
                            }
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mIsFlipping) {
                        final VelocityTracker velocityTracker = mVelocityTracker;
                        velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);

                        int velocity = 0;
                        if (isFlippingVertically()) {
                            velocity = (int) velocityTracker.getYVelocity(
                                    mActivePointerId);
                        } else {
                            velocity = (int) velocityTracker.getXVelocity(
                                    mActivePointerId);
                        }
                        smoothFlipTo(getNextPage(velocity));

                        mActivePointerId = INVALID_POINTER;
                        endFlip();

                        mOverFlipper.overFlipEnded();
                    }
                    break;
                case MotionEvent.ACTION_POINTER_DOWN: {
                    final int index = ev.getActionIndex();
                    final float x = ev.getX(index);
                    final float y = ev.getY(index);
                    mLastX = x;
                    mLastY = y;
                    mActivePointerId = ev.getPointerId(index);
                    break;
                }
                case MotionEvent.ACTION_POINTER_UP:
                    onSecondaryPointerUp(ev);
                    final int index = ev.findPointerIndex(
                            mActivePointerId);
                    final float x = ev.getX(index);
                    final float y = ev.getY(index);
                    mLastX = x;
                    mLastY = y;
                    break;
            }
            if (mActivePointerId == INVALID_POINTER) {
                mLastTouchAllowed = false;
            }
        }
        return true;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!mIsFlippingEnabled) {
            mIsFlippingEnabled = true;
        }
        if (mPageCount < 1) {
            return;
        }

        if (!mScroller.isFinished() && mScroller.computeScrollOffset()) {
            if (!mIsFlippingCascade) {
                setFlipDistance(mScroller.getCurrY(), false);
            } else {
                setCascadeFlipDistance(mScroller.getCurrY());
            }
        }

        if (mIsFlipping || !mScroller.isFinished() || mPeakAnim != null) {
            if (!mIsFlippingCascade) {
                drawSequential(canvas);
            } else {
                drawCascade(canvas);
            }
        } else {
            endScroll();
            if (mIsFlippingCascade) {
                drawCascade(canvas);
                if (!mIsCascadeAnimationPrepared) {
                    mFlipDistance = mCascadeEndFlipDistance;
                }
            } else if (mFlipDistance % FLIP_DISTANCE_PER_PAGE > EPSILON) {
                mIsFlippingToDistance = false;
                mFlipDistance = mCascadeEndFlipDistance;
                drawSequential(canvas);
            } else {
                setDrawWithLayer(mCurrentPage.v, false);
                hideOtherPages(mCurrentPage);
                drawChild(canvas, mCurrentPage.v, 0);
            }

            // dispatch listener event now that we have "landed" on a page.
            // TODO not the prettiest to have this with the drawing logic,
            // should change.
            if (mLastDispatchedPageEventIndex != mCurrentPageIndex) {
                mLastDispatchedPageEventIndex = mCurrentPageIndex;
                postFlippedToPage(mCurrentPageIndex);
            }
        }

        // if overflip is GLOW mode and the edge effects needed drawing, make
        // sure to invalidate
        if (mDrawOverFlip) {
            if (mOverFlipper.draw(canvas)) {
                // always invalidate whole screen as it is needed 99% of the time.
                // This is because of the shadows and shines put on the non-flipping
                // pages
                invalidate();
            }
        }

        // invalidate for cascade
        if (mFlipDistance != mCascadeEndFlipDistance && mCascadeEndFlipDistance != INVALID_FLIP_DISTANCE) {
            invalidate();
        }
    }

    private void drawSequential(Canvas canvas) {
        showAllPages();
        drawPreviousHalf(canvas);
        drawNextHalf(canvas);
        drawFlippingHalf(canvas);
    }

    private void drawCascade(Canvas canvas) {
        setDrawWithLayer(this, true);
        final int prevViewIdx = getPrevViewIdx();
        final int nextViewIdx = getNextViewIdx();
        drawCascadePreviousHalf(canvas, prevViewIdx);
        drawCascadeNextHalf(canvas, nextViewIdx);

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN_MR2) {
            drawApi18(canvas);
        }

        drawCascadeFlippingHalf(canvas, prevViewIdx, nextViewIdx);
    }

    private int getNextViewIdx() {
        if (mFlipDistance == 0) {
            return 0;
        } else if (((mFlipDistance / mCascadeOffset) + 1) > (mCascadeViews.size() - 1)) {
            return mCascadeViews.size() - 1;
        } else {
            return (int) (mFlipDistance / mCascadeOffset + 1);
        }
    }

    private int getPrevViewIdx() {
        final float distance = mFlipDistance - 180;
        if (distance < 0) {
            return 0;
        } else {
            return (int) (distance / mCascadeOffset) + 1;
        }
    }

    private void drawCascadePreviousHalf(Canvas canvas, int prevViewIdx) {
        if (prevViewIdx < mCascadeViews.size() && prevViewIdx >= 0) {
            canvas.save();
            canvas.clipRect(isFlippingVertically() ? mTopRect : mLeftRect);

            final View v = mCascadeViews.get(prevViewIdx);
            setDrawWithLayer(v, true);
            drawChild(canvas, v, 0);

            canvas.restore();
        }
    }

    private void drawCascadeNextHalf(Canvas canvas, int nextViewIdx) {
        if (nextViewIdx < mCascadeViews.size() && nextViewIdx >= 0) {
            canvas.save();
            canvas.clipRect(isFlippingVertically() ? mBottomRect : mRightRect);

            final View v = mCascadeViews.get(nextViewIdx);
            setDrawWithLayer(v, true);
            drawChild(canvas, v, 0);

            canvas.restore();
        }
    }

    private void drawApi18(Canvas canvas) {
        if (mFlipDistance >= 90) {
            canvas.save();
            final Rect drawingRect = isFlippingVertically() ? mBottomRect : mRightRect;
            canvas.clipRect(drawingRect);
            if (mBitmap != null) {
                canvas.drawBitmap(mBitmap, drawingRect, drawingRect, null);
            }
            canvas.restore();
        }

        if (mCascadeEndFlipDistance - mFlipDistance <= 90) {
            canvas.save();
            final Rect drawingRect = isFlippingVertically() ? mTopRect : mLeftRect;
            canvas.clipRect(drawingRect);
            if (mBitmapR != null) {
                canvas.drawBitmap(mBitmapR, drawingRect, drawingRect, null);
            }
            canvas.restore();
        }
    }

    private void drawCascadeFlippingHalf(Canvas canvas, int prevViewIdx, int nextViewIdx) {
        if (nextViewIdx < mCascadeViews.size() && nextViewIdx >= 0 &&
                prevViewIdx < mCascadeViews.size() && prevViewIdx >= 0) {
            // get drawing order
            final List<CascadeViewHolder> views = new ArrayList<>();
            CascadeViewHolder holder;
            for (int i = nextViewIdx - 1; i >= prevViewIdx; i--) {
                holder = new CascadeViewHolder();
                holder.degreesFlipped = getCascadeDegreesFlipped(i);
                if (holder.degreesFlipped > 90) {
                    holder.v = mCascadeViews.get(i + 1);
                    holder.drawingOrder = mCascadeViews.size() - 1 - i;
                } else {
                    holder.v = mCascadeViews.get(i);
                    holder.drawingOrder = i;
                }
                views.add(holder);
            }

            // sort depending on drawing order
            Collections.sort(views, Collections.reverseOrder());

            // draw
            for (int i = 0; i < views.size(); i++) {
                holder = views.get(i);
                canvas.save();
                mCamera.save();

                if (holder.degreesFlipped > 90) {
                    canvas.clipRect(isFlippingVertically() ? mTopRect : mLeftRect);
                    if (mIsFlippingVertically) {
                        mCamera.rotateX(holder.degreesFlipped - 180);
                    } else {
                        mCamera.rotateY(180 - holder.degreesFlipped);
                    }
                } else {
                    canvas.clipRect(isFlippingVertically() ? mBottomRect : mRightRect);
                    if (mIsFlippingVertically) {
                        mCamera.rotateX(holder.degreesFlipped);
                    } else {
                        mCamera.rotateY(-holder.degreesFlipped);
                    }
                }

                mCamera.getMatrix(mMatrix);

                positionMatrix();
                canvas.concat(mMatrix);

                setDrawWithLayer(holder.v, true);
                drawChild(canvas, holder.v, 0);

                mCamera.restore();
                canvas.restore();
            }

            views.clear();
        }
    }

    private float getCascadeDegreesFlipped(int idx) {
        float localFlipDistance = (mFlipDistance - idx * mCascadeOffset) % FLIP_DISTANCE_PER_PAGE;

        // fix for negative modulo. always want a positive flip degree
        if (localFlipDistance < 0) {
            localFlipDistance += FLIP_DISTANCE_PER_PAGE;
        }

        return (localFlipDistance / FLIP_DISTANCE_PER_PAGE) * 180;
    }

    private void hideOtherPages(Page p) {
        if (mPreviousPage != p && mPreviousPage.valid && mPreviousPage.v.getVisibility() != GONE) {
            mPreviousPage.v.setVisibility(GONE);
        }
        if (mCurrentPage != p && mCurrentPage.valid && mCurrentPage.v.getVisibility() != GONE) {
            mCurrentPage.v.setVisibility(GONE);
        }
        if (mNextPage != p && mNextPage.valid && mNextPage.v.getVisibility() != GONE) {
            mNextPage.v.setVisibility(GONE);
        }
        p.v.setVisibility(VISIBLE);
    }

    private void showAllPages() {
        if (mPreviousPage.valid && mPreviousPage.v.getVisibility() != VISIBLE) {
            mPreviousPage.v.setVisibility(VISIBLE);
        }
        if (mCurrentPage.valid && mCurrentPage.v.getVisibility() != VISIBLE) {
            mCurrentPage.v.setVisibility(VISIBLE);
        }
        if (mNextPage.valid && mNextPage.v.getVisibility() != VISIBLE) {
            mNextPage.v.setVisibility(VISIBLE);
        }
    }

    /**
     * draw top/left half
     *
     * @param canvas
     */
    private void drawPreviousHalf(Canvas canvas) {
        canvas.save();
        final Rect drawingRect = isFlippingVertically() ? mTopRect : mLeftRect;
        canvas.clipRect(drawingRect);

        final float degreesFlipped = getDegreesFlipped();
        final Page p = degreesFlipped >= 90 ? mPreviousPage : mCurrentPage;

        // if the view does not exist, skip drawing it
        if (p.valid) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN_MR2 && degreesFlipped < 90) {
                p.v.draw(mCanvas);
                if (mBitmap != null) {
                    canvas.drawBitmap(mBitmap, drawingRect, drawingRect, null);
                }
            } else {
                setDrawWithLayer(p.v, true);
                drawChild(canvas, p.v, 0);
            }
        }

        if (mDrawShadows) {
            drawPreviousShadow(canvas);
        }
        canvas.restore();
    }

    /**
     * draw top/left half shadow
     *
     * @param canvas
     */
    private void drawPreviousShadow(Canvas canvas) {
        final float degreesFlipped = getDegreesFlipped();
        if (degreesFlipped > 90) {
            final int alpha = (int) (((degreesFlipped - 90) / 90f) * MAX_SHADOW_ALPHA);
            mShadowPaint.setAlpha(alpha);
            canvas.drawPaint(mShadowPaint);
        }
    }

    /**
     * draw bottom/right half
     *
     * @param canvas
     */
    private void drawNextHalf(Canvas canvas) {
        canvas.save();
        final Rect drawingRect = isFlippingVertically() ? mBottomRect : mRightRect;
        canvas.clipRect(drawingRect);

        final float degreesFlipped = getDegreesFlipped();
        final Page p = degreesFlipped >= 90 ? mCurrentPage : mNextPage;

        // if the view does not exist, skip drawing it
        if (p.valid) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.JELLY_BEAN_MR2 && degreesFlipped >= 90) {
                p.v.draw(mCanvas);
                if (mBitmap != null) {
                    canvas.drawBitmap(mBitmap, drawingRect, drawingRect, null);
                }
            } else {
                setDrawWithLayer(p.v, true);
                drawChild(canvas, p.v, 0);
            }
        }

        if (mDrawShadows) {
            drawNextShadow(canvas);
        }
        canvas.restore();
    }

    /**
     * draw bottom/right half shadow
     *
     * @param canvas
     */
    private void drawNextShadow(Canvas canvas) {
        final float degreesFlipped = getDegreesFlipped();
        if (degreesFlipped > 0 && degreesFlipped <= 90) {
            final int alpha = (int) ((Math.abs(degreesFlipped - 90) / 90f) * MAX_SHADOW_ALPHA);
            mShadowPaint.setAlpha(alpha);
            canvas.drawPaint(mShadowPaint);
        }
    }

    private void drawFlippingHalf(Canvas canvas) {
        canvas.save();
        mCamera.save();

        final float degreesFlipped = getDegreesFlipped();

        if (degreesFlipped > 90) {
            canvas.clipRect(isFlippingVertically() ? mTopRect : mLeftRect);
            if (mIsFlippingVertically) {
                mCamera.rotateX(degreesFlipped - 180);
            } else {
                mCamera.rotateY(180 - degreesFlipped);
            }
        } else {
            canvas.clipRect(isFlippingVertically() ? mBottomRect : mRightRect);
            if (mIsFlippingVertically) {
                mCamera.rotateX(degreesFlipped);
            } else {
                mCamera.rotateY(-degreesFlipped);
            }
        }

        mCamera.getMatrix(mMatrix);

        positionMatrix();
        canvas.concat(mMatrix);

        setDrawWithLayer(mCurrentPage.v, true);
        drawChild(canvas, mCurrentPage.v, 0);

        if (mDrawShadesAndShines) {
            drawFlippingShadeShine(canvas);
        }

        if (mDrawGradient) {
            drawGradient(canvas);
        }

        mCamera.restore();
        canvas.restore();
    }

    /**
     * will draw gradient over flipping half
     *
     * @param canvas
     */
    private void drawGradient(Canvas canvas) {
        final float degreesFlipped = getDegreesFlipped();
        if (degreesFlipped > 0 && degreesFlipped <= 90) {
            final int alpha = (int) ((1 - (90 - degreesFlipped) / 90f) * MAX_GRADIENT_ALPHA);
            mGradientPaint.setAlpha(alpha);
            canvas.drawRect(isFlippingVertically() ? mBottomRect : mRightRect, mGradientPaint);
        } else if (degreesFlipped > 90) {
            final int alpha = (int) (((180 - degreesFlipped) / 90f) * MAX_GRADIENT_ALPHA);
            mFlippedGradientPaint.setAlpha(alpha);
            canvas.drawRect(isFlippingVertically() ? mTopRect : mLeftRect, mFlippedGradientPaint);
        }
    }

    /**
     * will draw a shade if flipping on the previous(top/left) half and a shine
     * if flipping on the next(bottom/right) half
     *
     * @param canvas
     */
    private void drawFlippingShadeShine(Canvas canvas) {
        final float degreesFlipped = getDegreesFlipped();
        if (degreesFlipped < 90) {
            final int alpha = (int) ((degreesFlipped / 90f) * MAX_SHINE_ALPHA);
            mShinePaint.setAlpha(alpha);
            canvas.drawRect(isFlippingVertically() ? mBottomRect : mRightRect,
                    mShinePaint);
        } else {
            final int alpha = (int) ((Math.abs(degreesFlipped - 180) / 90f) * MAX_SHADE_ALPHA);
            mShadePaint.setAlpha(alpha);
            canvas.drawRect(isFlippingVertically() ? mTopRect : mLeftRect,
                    mShadePaint);
        }
    }

    /**
     * Enable a hardware layer for the view.
     *
     * @param v
     * @param drawWithLayer
     */
    private void setDrawWithLayer(View v, boolean drawWithLayer) {
        if (isHardwareAccelerated()) {
            if (v.getLayerType() != LAYER_TYPE_HARDWARE && drawWithLayer) {
                v.setLayerType(LAYER_TYPE_HARDWARE, null);
            } else if (v.getLayerType() != LAYER_TYPE_NONE && !drawWithLayer) {
                v.setLayerType(LAYER_TYPE_NONE, null);
            }
        }
    }

    private void positionMatrix() {
        mMatrix.preScale(0.1f, 0.1f);
        mMatrix.postScale(10.0f, 10.0f);
        mMatrix.preTranslate(-getWidth() / 2, -getHeight() / 2);
        mMatrix.postTranslate(getWidth() / 2, getHeight() / 2);
    }

    private float getDegreesFlipped() {
        float localFlipDistance = mFlipDistance % FLIP_DISTANCE_PER_PAGE;

        // fix for negative modulo. always want a positive flip degree
        if (localFlipDistance < 0) {
            localFlipDistance += FLIP_DISTANCE_PER_PAGE;
        }

        return (localFlipDistance / FLIP_DISTANCE_PER_PAGE) * 180;
    }

    private void postFlippedToPage(final int page) {
        post(new Runnable() {

            @Override
            public void run() {
                if (mOnFlipListener != null) {
                    final long itemId = page >= mAdapter.getCount() ? 0 : mAdapter.getItemId(page);
                    mOnFlipListener.onFlippedToPage(FlipView.this, page,
                            itemId);
                }
            }
        });
    }

    private void postDistancePassed() {
        post(new Runnable() {
            @Override
            public void run() {
                if (mOnDistanceListener != null) {
                    mOnDistanceListener.onDistanceChange(mFlipDistance);
                }
            }
        });
    }

    private void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = ev.getActionIndex();
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastX = ev.getX(newPointerIndex);
            mActivePointerId = ev.getPointerId(
                    newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }

    /**
     * @param deltaFlipDistance The distance to flip.
     * @return The duration for a flip, bigger deltaFlipDistance = longer
     * duration. The increase in duration gets smaller for bigger values
     * of deltaFlipDistance.
     */
    private int getFlipDuration(int deltaFlipDistance) {
        float distance = Math.abs(deltaFlipDistance);
        return (int) (mMaxSinglePageFlipAnimDuration * Math.sqrt(distance
                / FLIP_DISTANCE_PER_PAGE));
    }

    /**
     * @param velocity
     * @return the page you should "land" on
     */
    private int getNextPage(int velocity) {
        int nextPage;
        if (velocity > mMinimumVelocity) {
            nextPage = getCurrentPageFloor();
        } else if (velocity < -mMinimumVelocity) {
            nextPage = getCurrentPageCeil();
        } else {
            nextPage = getCurrentPageRound();
        }
        return Math.min(Math.max(nextPage, 0), mPageCount - 1);
    }

    private int getCurrentPageRound() {
        return Math.round(mFlipDistance / FLIP_DISTANCE_PER_PAGE);
    }

    private int getCurrentPageFloor() {
        return (int) Math.floor(mFlipDistance / FLIP_DISTANCE_PER_PAGE);
    }

    private int getCurrentPageCeil() {
        return (int) Math.ceil(mFlipDistance / FLIP_DISTANCE_PER_PAGE);
    }

    /**
     * @return true if ended a flip
     */
    private boolean endFlip() {
        final boolean wasflipping = mIsFlipping;
        mIsFlipping = false;
        mIsUnableToFlip = false;
        mLastTouchAllowed = false;

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        return wasflipping;
    }

    /**
     * @return true if ended a scroll
     */
    private boolean endScroll() {
        final boolean wasScrolling = !mScroller.isFinished();
        mScroller.abortAnimation();
        return wasScrolling;
    }

    /**
     * @return true if ended a peak
     */
    private boolean endPeak() {
        final boolean wasPeaking = mPeakAnim != null;
        if (mPeakAnim != null) {
            mPeakAnim.cancel();
            mPeakAnim = null;
        }
        return wasPeaking;
    }

    private void peak(boolean next, boolean once) {
        final float baseFlipDistance = mCurrentPageIndex
                * FLIP_DISTANCE_PER_PAGE;
        if (next) {
            mPeakAnim = ValueAnimator.ofFloat(baseFlipDistance,
                    baseFlipDistance + FLIP_DISTANCE_PER_PAGE / 4);
        } else {
            mPeakAnim = ValueAnimator.ofFloat(baseFlipDistance,
                    baseFlipDistance - FLIP_DISTANCE_PER_PAGE / 4);
        }
        mPeakAnim.setInterpolator(mPeakInterpolator);
        mPeakAnim.addUpdateListener(new AnimatorUpdateListener() {

            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setFlipDistance((Float) animation.getAnimatedValue(), true);
            }
        });
        mPeakAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                endPeak();
            }
        });
        mPeakAnim.setDuration(PEAK_ANIM_DURATION);
        mPeakAnim.setRepeatMode(ValueAnimator.REVERSE);
        mPeakAnim.setRepeatCount(once ? 1 : ValueAnimator.INFINITE);
        mPeakAnim.start();
    }

    private void trackVelocity(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
    }

    private void updateEmptyStatus() {
        boolean empty = mAdapter == null || mPageCount == 0;

        if (empty) {
            if (mEmptyView != null) {
                mEmptyView.setVisibility(View.VISIBLE);
                setVisibility(View.GONE);
            } else {
                setVisibility(View.VISIBLE);
            }

        } else {
            if (mEmptyView != null) {
                mEmptyView.setVisibility(View.GONE);
            }
            setVisibility(View.VISIBLE);
        }
    }

    /* ---------- API ---------- */

    /**
     * @param adapter a regular ListAdapter, not all methods if the list adapter are
     *                used by the flipview
     */
    public void setAdapter(ListAdapter adapter) {
        if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(dataSetObserver);
        }

        // remove all the current views
        removeAllViews();
        mCascadeViews.clear();
        mIsCascadeAnimationPrepared = false;

        mAdapter = adapter;
        mPageCount = adapter == null ? 0 : mAdapter.getCount();

        if (adapter != null) {
            mAdapter.registerDataSetObserver(dataSetObserver);

            mRecycler.setViewTypeCount(mAdapter.getViewTypeCount());
            mRecycler.invalidateScraps();
        }

        // TODO pretty confusing
        // this will be correctly set in setFlipDistance method
        mLastDispatchedPageEventIndex = -1;
        mCurrentPageIndex = INVALID_PAGE_POSITION;
        mFlipDistance = INVALID_FLIP_DISTANCE;
        setFlipDistance(0, true);

        updateEmptyStatus();
    }

    public void setDrawShadows(boolean enabled) {
        mDrawShadows = enabled;
    }

    public void setDrawOverFlip(boolean enabled) {
        mDrawOverFlip = enabled;
    }

    public void setDrawShadesAndShines(boolean enabled) {
        mDrawShadesAndShines = enabled;
    }

    public void setDrawGradient(boolean enabled) {
        mDrawGradient = enabled;
    }

    public void setShadowPaintColor(int color) {
        mShadowPaint.setColor(color);
    }

    public void setGradient(int color0, int color1) {
        mGradientColor0 = color0;
        mGradientColor1 = color1;
    }

    private void initializeGradient() {
        final Rect rect = isFlippingVertically() ? mBottomRect : mRightRect;
        final LinearGradient gradient = new LinearGradient(rect.centerX(), rect.top,
                rect.centerX(), rect.bottom, mGradientColor0, mGradientColor1, Shader.TileMode.CLAMP);
        mGradientPaint.setShader(gradient);
        final Rect flippedRect = isFlippingVertically() ? mTopRect : mLeftRect;
        final LinearGradient flippedGradient = new LinearGradient(flippedRect.centerX(), flippedRect.top,
                flippedRect.centerX(), flippedRect.bottom, mGradientColor1, mGradientColor0, Shader.TileMode.CLAMP);
        mFlippedGradientPaint.setShader(flippedGradient);
    }

    public void setFlippingCascade(boolean enabled) {
        if (!enabled && mCurrentPageIndex != INVALID_PAGE_POSITION) {
            removeAllViews();
            mCascadeViews.clear();
            mIsCascadeAnimationPrepared = false;
            mFlipDistance = mCurrentPageIndex * FLIP_DISTANCE_PER_PAGE;
            mCurrentPageIndex = -1;
        }
        mIsFlippingCascade = enabled;
    }

    public void setCascadeFlippingOffset(int offset) {
        mCascadeOffset = offset;
    }

    public void setMaxSinglePageFlipAnimDuration(int duration) {
        mMaxSinglePageFlipAnimDuration = duration;
    }

    public void setCascadeFlipDuration(int duration) {
        mCascadeFlipDuration = duration;
    }

    public ListAdapter getAdapter() {
        return mAdapter;
    }

    public int getPageCount() {
        return mPageCount;
    }

    public int getCurrentPage() {
        return mCurrentPageIndex;
    }

    public void flipTo(int page) {
        mIsFlippingEnabled = false;
        setFlippingCascade(false);
        mIsFlippingToDistance = false;
        mCascadeEndFlipDistance = INVALID_FLIP_DISTANCE;
        if (page < 0 || page > mPageCount - 1) {
            throw new IllegalArgumentException("Flipping to page " + page + " page count " + mPageCount);
        }
        endFlip();
        setFlipDistance(page * FLIP_DISTANCE_PER_PAGE, true);
    }

    public void flipBy(int delta) {
        flipTo(mCurrentPageIndex + delta);
    }

    public void smoothFlipTo(int page) {
        if (page < 0 || page > mPageCount - 1) {
            throw new IllegalArgumentException("That page does not exist");
        }
        mIsFlippingToDistance = false;
        final int start = (int) mFlipDistance;

        if (!mIsFlippingCascade) {
            final int delta = page * FLIP_DISTANCE_PER_PAGE - start;
            endFlip();
            mCascadeEndFlipDistance = start + delta;
            mScroller.startScroll(0, start, 0, delta, getFlipDuration(delta));
        } else {
            if (mCurrentPageIndex < page) {
                mFlipDistance = 0;
            } else {
                mFlipDistance = FLIP_DISTANCE_PER_PAGE + mCascadeOffset * (getPageCount() - 2);
            }

            mCurrentPageIndex = page;
            mIsCascadeAnimationPrepared = false;
            mScroller.startScroll(0, (int) mFlipDistance, 0, (int) (mCascadeEndFlipDistance - mFlipDistance), mCascadeFlipDuration);
        }

        invalidate();
    }

    public void prepareCascadeFlip(int page) {
        endFlip();
        // get views from adapter to draw
        recycleActiveViews();
        removeAllViews();
        mCascadeViews.clear();
        // TODO: takes all views between the current one and the destination one, should be limited by a number
        if (mCurrentPageIndex < page) {
            mFlipDistance = 0;
            mCascadeEndFlipDistance = FLIP_DISTANCE_PER_PAGE + mCascadeOffset * (getPageCount() - 2);
            for (int i = mCurrentPageIndex; i <= page; i++) {
                mCascadeViews.add(mAdapter.getView(i, null, this));
            }
        } else {
            for (int i = page; i <= mCurrentPageIndex; i++) {
                mCascadeViews.add(mAdapter.getView(i, null, this));
            }
            mFlipDistance = FLIP_DISTANCE_PER_PAGE + mCascadeOffset * (getPageCount() - 2);
            mCascadeEndFlipDistance = 0;
        }

        for (int i = 0; i < mCascadeViews.size(); i++) {
            addView(mCascadeViews.get(i));
            mCascadeViews.get(i).setVisibility(VISIBLE);
        }
        mCascadeBitmapsReady = false;
        mIsCascadeAnimationPrepared = true;
    }

    public void smoothFlipBy(int delta) {
        smoothFlipTo(mCurrentPageIndex + delta);
    }

    public void smoothFlipToDistance(int distance, int flipDuration) {
        if (distance < 0 || distance > (mPageCount - 1) * FLIP_DISTANCE_PER_PAGE) {
            throw new IllegalArgumentException("That distance does not exist");
        }
        final int start = (int) mFlipDistance;

        if (!mIsFlippingCascade) {
            final int delta = distance - start;
            endFlip();
            mCascadeEndFlipDistance = start + delta;
            mIsFlippingToDistance = true;
            if (flipDuration == -1) {
                flipDuration = getFlipDuration(delta);
            }
            mScroller.startScroll(0, start, 0, delta, flipDuration);
        }

        invalidate();
    }

    public void setFlipDistance(float flipDistance) {
        endFlip();
        mIsFlippingToDistance = true;
        mCascadeEndFlipDistance = (int) flipDistance;
        setFlipDistance(flipDistance, true);
    }

    public float getFlipDistance() {
        return mFlipDistance;
    }

    /**
     * Hint that there is a next page will do nothing if there is no next page
     *
     * @param once if true, only peak once. else peak until user interacts with
     *             view
     */
    public void peakNext(boolean once) {
        if (mCurrentPageIndex < mPageCount - 1) {
            peak(true, once);
        }
    }

    /**
     * Hint that there is a previous page will do nothing if there is no
     * previous page
     *
     * @param once if true, only peak once. else peak until user interacts with
     *             view
     */
    public void peakPrevious(boolean once) {
        if (mCurrentPageIndex > 0) {
            peak(false, once);
        }
    }

    /**
     * @return true if the view is flipping vertically, can only be set via xml
     * attribute "orientation"
     */
    public boolean isFlippingVertically() {
        return mIsFlippingVertically;
    }

    /**
     * The OnFlipListener will notify you when a page has been fully turned.
     *
     * @param onFlipListener
     */
    public void setOnFlipListener(OnFlipListener onFlipListener) {
        mOnFlipListener = onFlipListener;
    }

    /**
     * The OnDistanceListener will notify you when a distance point is passed forward (true) or backwards (false).
     *
     * @param onDistanceListener
     */
    public void setOnDistanceListener(OnDistanceListener onDistanceListener) {
        mOnDistanceListener = onDistanceListener;
    }

    /**
     * The OnOverFlipListener will notify of over flipping. This is a great
     * listener to have when implementing pull-to-refresh
     *
     * @param onOverFlipListener
     */
    public void setOnOverFlipListener(OnOverFlipListener onOverFlipListener) {
        this.mOnOverFlipListener = onOverFlipListener;
    }

    /**
     * @return the overflip mode of this flipview. Default is GLOW
     */
    public OverFlipMode getOverFlipMode() {
        return mOverFlipMode;
    }

    /**
     * Set the overflip mode of the flipview. GLOW is the standard seen in all
     * andriod lists. RUBBER_BAND is more like iOS lists which list you flip
     * past the first/last page but adding friction, like a rubber band.
     *
     * @param overFlipMode
     */
    public void setOverFlipMode(OverFlipMode overFlipMode) {
        this.mOverFlipMode = overFlipMode;
        mOverFlipper = OverFlipperFactory.create(this, mOverFlipMode);
    }

    /**
     * @param emptyView The view to show when either no adapter is set or the adapter
     *                  has no items. This should be a view already in the view
     *                  hierarchy which the FlipView will set the visibility of.
     */
    public void setEmptyView(View emptyView) {
        mEmptyView = emptyView;
        updateEmptyStatus();
    }
}