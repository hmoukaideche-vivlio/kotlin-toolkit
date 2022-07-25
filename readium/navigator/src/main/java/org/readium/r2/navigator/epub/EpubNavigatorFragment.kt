/*
 * Copyright 2020 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.navigator.epub

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.collection.forEach
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.viewModels
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.readium.r2.navigator.*
import org.readium.r2.navigator.databinding.ActivityR2ViewpagerBinding
import org.readium.r2.navigator.epub.EpubNavigatorViewModel.RunScriptCommand
import org.readium.r2.navigator.extensions.optRectF
import org.readium.r2.navigator.extensions.positionsByResource
import org.readium.r2.navigator.extensions.withBaseUrl
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.navigator.pager.R2EpubPageFragment
import org.readium.r2.navigator.pager.R2PagerAdapter
import org.readium.r2.navigator.pager.R2PagerAdapter.PageResource
import org.readium.r2.navigator.pager.R2ViewPager
import org.readium.r2.navigator.settings.*
import org.readium.r2.navigator.util.createFragmentFactory
import org.readium.r2.shared.COLUMN_COUNT_REF
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.SCROLL_REF
import org.readium.r2.shared.extensions.addPrefix
import org.readium.r2.shared.extensions.tryOrLog
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.ReadingProgression
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.launchWebBrowser
import org.readium.r2.shared.util.mediatype.MediaType
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/**
 * Factory for a [JavascriptInterface] which will be injected in the web views.
 *
 * Return `null` if you don't want to inject the interface for the given [resource].
 */
typealias JavascriptInterfaceFactory = (resource: Link) -> Any?

/**
 * Navigator for EPUB publications.
 *
 * To use this [Fragment], create a factory with `EpubNavigatorFragment.createFactory()`.
 */
@OptIn(ExperimentalDecorator::class, ExperimentalReadiumApi::class)
class EpubNavigatorFragment private constructor(
    override val publication: Publication,
    private val baseUrl: String,
    private val initialLocator: Locator?,
    internal val listener: Listener?,
    internal val paginationListener: PaginationListener?,
    config: Configuration,
) : Fragment(), VisualNavigator, SelectableNavigator, DecorableNavigator, Configurable {

    // Make a copy to prevent the user from modifying the configuration after initialization.
    internal val config: Configuration = config.copy()

    data class Configuration(
        /**
         * Initial set of setting preferences.
         */
        val preferences: Preferences = Preferences(),

        /**
         * Fallback preferences when missing.
         */
        val defaultPreferences: Preferences = Preferences(),

        /**
         * Available font families for reflowable resources.
         */
        val fonts: List<Font> = DEFAULT_FONTS,

        /**
         * Supported HTML decoration templates.
         */
        val decorationTemplates: HtmlDecorationTemplates = HtmlDecorationTemplates.defaultTemplates(),

        /**
         * Custom [ActionMode.Callback] to be used when the user selects content.
         *
         * Provide one if you want to customize the selection context menu items.
         */
        var selectionActionModeCallback: ActionMode.Callback? = null,

        /**
         * Whether padding accounting for display cutouts should be applied.
         */
        val shouldApplyInsetsPadding: Boolean? = true,

        internal val javascriptInterfaces: MutableMap<String, JavascriptInterfaceFactory> = mutableMapOf()
    ) {
        /**
         * Registers a new factory for the [JavascriptInterface] named [name].
         *
         * Return `null` in [factory] to prevent adding the Javascript interface for a given
         * resource.
         */
        fun registerJavascriptInterface(name: String, factory: JavascriptInterfaceFactory) {
            javascriptInterfaces[name] = factory
        }

        companion object {
            val DEFAULT_FONTS: List<Font> = listOf(
                Font.PT_SERIF, Font.ROBOTO, Font.SOURCE_SANS_PRO, Font.VOLLKORN,
                Font.OPEN_DYSLEXIC, Font.ACCESSIBLE_DFA, Font.IA_WRITER_DUOSPACE,
            )
        }
    }

    interface PaginationListener {
        fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {}
        fun onPageLoaded() {}
    }

    interface Listener: VisualNavigator.Listener

    init {
        require(!publication.isRestricted) { "The provided publication is restricted. Check that any DRM was properly unlocked using a Content Protection."}
    }

    // Configurable

    override val settings: StateFlow<EpubSettings> get() = viewModel.settings

    override fun applyPreferences(preferences: Preferences) {
        viewModel.applyPreferences(preferences)
    }

    /**
     * Evaluates the given JavaScript on the currently visible HTML resource.
     *
     * Note that this only work with reflowable resources.
     */
    suspend fun evaluateJavascript(script: String): String? {
        val page = currentReflowablePageFragment ?: return null
        page.awaitLoaded()
        val webView = page.webView ?: return null
        return webView.runJavaScriptSuspend(script)
    }

    private val viewModel: EpubNavigatorViewModel by viewModels {
        EpubNavigatorViewModel.createFactory(config)
    }

    internal lateinit var positionsByReadingOrder: List<List<Locator>>
    internal lateinit var positions: List<Locator>
    lateinit var resourcePager: R2ViewPager

    private lateinit var resourcesSingle: List<PageResource>
    private lateinit var resourcesDouble: List<PageResource>

    lateinit var preferences: SharedPreferences
    internal lateinit var publicationIdentifier: String

    internal var currentPagerPosition: Int = 0
    internal lateinit var adapter: R2PagerAdapter
    private lateinit var currentActivity: FragmentActivity

    internal var navigatorDelegate: NavigatorDelegate? = null

    private val r2Activity: R2EpubActivity? get() = activity as? R2EpubActivity

    private var _binding: ActivityR2ViewpagerBinding? = null
    private val binding get() = _binding!!

    override fun onAttach(context: Context) {
        super.onAttach(context)
        preferences = context.getSharedPreferences("org.readium.r2.settings", Context.MODE_PRIVATE)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        currentActivity = requireActivity()
        _binding = ActivityR2ViewpagerBinding.inflate(inflater, container, false)
        val view = binding.root

        positionsByReadingOrder = runBlocking { publication.positionsByReadingOrder() }
        positions = positionsByReadingOrder.flatten()
        publicationIdentifier = publication.metadata.identifier ?: publication.metadata.title

        resourcePager = binding.resourcePager
        resourcePager.type = Publication.TYPE.EPUB

        when (publication.metadata.presentation.layout) {
            EpubLayout.REFLOWABLE, null -> {
                resourcesSingle = publication.readingOrder.mapIndexed { index, link ->
                    PageResource.EpubReflowable(
                        link = link,
                        url = link.withBaseUrl(baseUrl).href,
                        positionCount = positionsByReadingOrder.getOrNull(index)?.size ?: 0
                    )
                }

                adapter = R2PagerAdapter(childFragmentManager, resourcesSingle)
                resourcePager.type = Publication.TYPE.EPUB
            }

            EpubLayout.FIXED -> {
                val resourcesSingle = mutableListOf<PageResource>()
                val resourcesDouble = mutableListOf<PageResource>()

                // TODO needs work, currently showing two resources for fxl, needs to understand which two resources, left & right, or only right etc.
                var doublePageLeft = ""
                var doublePageRight = ""

                for ((index, link) in publication.readingOrder.withIndex()) {
                    val url = link.withBaseUrl(baseUrl).href
                    resourcesSingle.add(PageResource.EpubFxl(url))

                    // add first page to the right,
                    if (index == 0) {
                        resourcesDouble.add(PageResource.EpubFxl("", url))
                    } else {
                        // add double pages, left & right
                        if (doublePageLeft == "") {
                            doublePageLeft = url
                        } else {
                            doublePageRight = url
                            resourcesDouble.add(
                                PageResource.EpubFxl(
                                    doublePageLeft,
                                    doublePageRight
                                )
                            )
                            doublePageLeft = ""
                        }
                    }
                }
                // add last page if there is only a left page remaining
                if (doublePageLeft != "") {
                    resourcesDouble.add(PageResource.EpubFxl(doublePageLeft, ""))
                }

                this.resourcesSingle = resourcesSingle
                this.resourcesDouble = resourcesDouble

                resourcePager.type = Publication.TYPE.FXL
                adapter = when (preferences.getInt(COLUMN_COUNT_REF, 0)) {
                    1 -> {
                        R2PagerAdapter(childFragmentManager, resourcesSingle)
                    }
                    2 -> {
                        R2PagerAdapter(childFragmentManager, resourcesDouble)
                    }
                    else -> {
                        // TODO based on device
                        // TODO decide if 1 page or 2 page
                        R2PagerAdapter(childFragmentManager, resourcesSingle)
                    }
                }
            }
        }
        adapter.listener = PagerAdapterListener()

        resourcePager.adapter = adapter
        resourcePager.direction = publication.metadata.effectiveReadingProgression

        if (publication.cssStyle == ReadingProgression.RTL.value) {
            resourcePager.direction = ReadingProgression.RTL
        }

        resourcePager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {

            override fun onPageSelected(position: Int) {
//                if (publication.metadata.presentation.layout == EpubLayout.REFLOWABLE) {
//                    resourcePager.disableTouchEvents = true
//                }
                currentReflowablePageFragment?.webView?.let { webView ->
                    if (preferences.getBoolean(SCROLL_REF, false)) {
                        if (currentPagerPosition < position) {
                            // handle swipe LEFT
                            webView.scrollToStart()
                        } else if (currentPagerPosition > position) {
                            // handle swipe RIGHT
                            webView.scrollToEnd()
                        }
                    } else {
                        if (currentPagerPosition < position) {
                            // handle swipe LEFT
                            webView.setCurrentItem(0, false)
                        } else if (currentPagerPosition > position) {
                            // handle swipe RIGHT
                            webView.setCurrentItem(webView.numPages - 1, false)
                        }
                    }
                }
                currentPagerPosition = position // Update current position

                notifyCurrentLocation()
            }

        })

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launch {
            viewModel.events
                .flowWithLifecycle(lifecycle)
                .onEach(::handleEvent)
                .launchIn(this)

            var previousSettings = viewModel.settings.value
            viewModel.settings
                .onEach {
                    onSettingsChange(previousSettings, it)
                    previousSettings = it
                }
                .launchIn(this)
        }

        // Restore the last locator before a configuration change (e.g. screen rotation), or the
        // initial locator when given.
        val locator = savedInstanceState?.getParcelable("locator") ?: initialLocator
        if (locator != null) {
            go(locator)
        }
    }

    private fun handleEvent(event: EpubNavigatorViewModel.Event) {
        when (event) {
            is EpubNavigatorViewModel.Event.RunScript -> run(event.command)
        }
    }

    private fun onSettingsChange(previous: EpubSettings, new: EpubSettings) {
        if (previous.fontSize.value != new.fontSize.value) {
            r2PagerAdapter?.setFontSize(new.fontSize.value)
        }
    }

    private fun R2PagerAdapter.setFontSize(fontSize: Double) {
        r2PagerAdapter?.mFragments?.forEach { _, fragment ->
            (fragment as? R2EpubPageFragment)?.setFontSize(fontSize)
        }
    }

    private inner class PagerAdapterListener : R2PagerAdapter.Listener {
        override fun onCreatePageFragment(fragment: Fragment) {
            (fragment as? R2EpubPageFragment)?.setFontSize(settings.value.fontSize.value)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("locator", currentLocator.value)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        notifyCurrentLocation()
    }

    override fun go(locator: Locator, animated: Boolean, completion: () -> Unit): Boolean {
        listener?.onJumpToLocator(locator)

        val href = locator.href
            // Remove anchor
            .substringBefore("#")

        fun setCurrent(resources: List<PageResource>) {
            val page = resources.withIndex().firstOrNull { (_, res) ->
                when (res) {
                    is PageResource.EpubReflowable -> res.link.href == href
                    is PageResource.EpubFxl -> res.url1.endsWith(href) || res.url2?.endsWith(href) == true
                    else -> false
                }
            } ?: return
            val (index, _) = page

            if (resourcePager.currentItem != index) {
                resourcePager.currentItem = index
            }
            r2PagerAdapter?.loadLocatorAt(index, locator)
        }

        if (publication.metadata.presentation.layout != EpubLayout.FIXED) {
            setCurrent(resourcesSingle)
        } else {

            when (preferences.getInt(COLUMN_COUNT_REF, 0)) {
                1 -> {
                    setCurrent(resourcesSingle)
                }
                2 -> {
                    setCurrent(resourcesDouble)
                }
                else -> {
                    // TODO based on device
                    // TODO decide if 1 page or 2 page
                    setCurrent(resourcesSingle)
                }
            }
        }

        return true
    }

    override fun go(link: Link, animated: Boolean, completion: () -> Unit): Boolean {
        val locator = publication.locatorFromLink(link) ?: return false
        return go(locator, animated, completion)
    }

    private fun run(commands: List<RunScriptCommand>) {
        commands.forEach { run(it) }
    }

    private fun run(command: RunScriptCommand) {
        when (command.scope) {
            RunScriptCommand.Scope.CurrentResource -> {
                currentReflowablePageFragment?.webView
                    ?.runJavaScript(command.script)
            }
            RunScriptCommand.Scope.LoadedResources -> {
                r2PagerAdapter?.mFragments?.forEach { _, fragment ->
                    (fragment as? R2EpubPageFragment)?.webView
                        ?.runJavaScript(command.script)
                }
            }
            is RunScriptCommand.Scope.Resource -> {
                loadedFragmentForHref(command.scope.href)?.webView
                    ?.runJavaScript(command.script)
            }
            is RunScriptCommand.Scope.WebView -> {
                command.scope.webView.runJavaScript(command.script)
            }
        }
    }

    // SelectableNavigator

    override suspend fun currentSelection(): Selection? {
        val webView = currentReflowablePageFragment?.webView ?: return null
        val json =
            webView.runJavaScriptSuspend("readium.getCurrentSelection();")
                .takeIf { it != "null"}
                ?.let { tryOrLog { JSONObject(it) } }
                ?: return null

        val rect = json.optRectF("rect")
            ?.run { adjustedToViewport() }

        return Selection(
            locator = currentLocator.value.copy(
                text = Locator.Text.fromJSON(json.optJSONObject("text"))
            ),
            rect = rect
        )
    }

    override fun clearSelection() {
        run(viewModel.clearSelection())
    }

    private fun PointF.adjustedToViewport(): PointF =
        currentReflowablePageFragment?.paddingTop?.let { top ->
            PointF(x, y + top)
        } ?: this

    private fun RectF.adjustedToViewport(): RectF =
        currentReflowablePageFragment?.paddingTop?.let { topOffset ->
            RectF(left, top + topOffset, right, bottom)
        } ?: this

    // DecorableNavigator

    override fun <T : Decoration.Style> supportsDecorationStyle(style: KClass<T>): Boolean =
        viewModel.supportsDecorationStyle(style)

    override fun addDecorationListener(group: String, listener: DecorableNavigator.Listener) {
        viewModel.addDecorationListener(group, listener)
    }

    override fun removeDecorationListener(listener: DecorableNavigator.Listener) {
        viewModel.removeDecorationListener(listener)
    }

    override suspend fun applyDecorations(decorations: List<Decoration>, group: String) {
        run(viewModel.applyDecorations(decorations, group))
    }

    // R2BasicWebView.Listener

    internal val webViewListener: R2BasicWebView.Listener = WebViewListener()

    @OptIn(ExperimentalDragGesture::class)
    private inner class WebViewListener : R2BasicWebView.Listener {

        override val readingProgression: ReadingProgression
            get() = this@EpubNavigatorFragment.readingProgression

        override fun onResourceLoaded(link: Link?, webView: R2BasicWebView, url: String?) {
            run(viewModel.onResourceLoaded(link, webView))
        }

        override fun onPageLoaded() {
            r2Activity?.onPageLoaded()
            paginationListener?.onPageLoaded()
            notifyCurrentLocation()
        }

        override fun onPageChanged(pageIndex: Int, totalPages: Int, url: String) {
            r2Activity?.onPageChanged(pageIndex = pageIndex, totalPages = totalPages, url = url)
        }

        override fun onPageEnded(end: Boolean) {
            r2Activity?.onPageEnded(end)
        }

        override fun javascriptInterfacesForResource(link: Link): Map<String, Any?> =
            config.javascriptInterfaces.mapValues { (_, factory) -> factory(link) }

        @Suppress("DEPRECATION")
        override fun onScroll() {
            val activity = r2Activity ?: return
            if (activity.supportActionBar?.isShowing == true && activity.allowToggleActionBar) {
                resourcePager.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_FULLSCREEN // hide status bar
                        or View.SYSTEM_UI_FLAG_IMMERSIVE)
            }
        }

        override fun onTap(point: PointF): Boolean =
            listener?.onTap(point.adjustedToViewport()) ?: false

        override fun onDragStart(event: R2BasicWebView.DragEvent): Boolean =
            listener?.onDragStart(
                startPoint = event.startPoint.adjustedToViewport(),
                offset = event.offset
            ) ?: false

        override fun onDragMove(event: R2BasicWebView.DragEvent): Boolean =
            listener?.onDragMove(
                startPoint = event.startPoint.adjustedToViewport(),
                offset = event.offset
            ) ?: false

        override fun onDragEnd(event: R2BasicWebView.DragEvent): Boolean =
            listener?.onDragEnd(
                startPoint = event.startPoint.adjustedToViewport(),
                offset = event.offset
            ) ?: false

        override fun onDecorationActivated(id: DecorationId, group: String, rect: RectF, point: PointF): Boolean =
            viewModel.onDecorationActivated(
                id = id,
                group = group,
                rect = rect.adjustedToViewport(),
                point = point.adjustedToViewport()
            )

        override fun onProgressionChanged() {
            notifyCurrentLocation()
        }

        override fun onHighlightActivated(id: String) {
            r2Activity?.highlightActivated(id)
        }

        override fun onHighlightAnnotationMarkActivated(id: String) {
            r2Activity?.highlightAnnotationMarkActivated(id)
        }

        override fun goForward(animated: Boolean, completion: () -> Unit): Boolean =
            goToNextResource(animated, completion)

        override fun goBackward(animated: Boolean, completion: () -> Unit): Boolean =
            goToPreviousResource(animated, completion)

        override val selectionActionModeCallback: ActionMode.Callback?
            get() = config.selectionActionModeCallback

        /**
         * Prevents opening external links in the web view and handles internal links.
         */
        override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString()
                ?: return false

            val baseUrl = baseUrl.takeIf { it.isNotBlank() }
                ?: publication.linkWithRel("self")?.href
                ?: return false

            if (!url.startsWith(baseUrl)) {
                openExternalLink(request.url)
            } else {
                // Navigate to an internal link
                go(Link(href = url.removePrefix(baseUrl).addPrefix("/")))
            }

            return true
        }

        private fun openExternalLink(url: Uri) {
            val context = context ?: return
            launchWebBrowser(context, url)
        }
    }

    override fun goForward(animated: Boolean, completion: () -> Unit): Boolean {
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            return goToNextResource(animated, completion)
        }

        val webView = currentReflowablePageFragment?.webView ?: return false

        when (readingProgression) {
            ReadingProgression.LTR, ReadingProgression.TTB, ReadingProgression.AUTO ->
                webView.scrollRight(animated)

            ReadingProgression.RTL, ReadingProgression.BTT ->
                webView.scrollLeft(animated)
        }
        viewLifecycleOwner.lifecycleScope.launch { completion() }
        return true
    }

    override fun goBackward(animated: Boolean, completion: () -> Unit): Boolean {
        if (publication.metadata.presentation.layout == EpubLayout.FIXED) {
            return goToPreviousResource(animated, completion)
        }

        val webView = currentReflowablePageFragment?.webView ?: return false

        when (readingProgression) {
            ReadingProgression.LTR, ReadingProgression.TTB, ReadingProgression.AUTO ->
                webView.scrollLeft(animated)

            ReadingProgression.RTL, ReadingProgression.BTT ->
                webView.scrollRight(animated)
        }
        viewLifecycleOwner.lifecycleScope.launch { completion() }
        return true
    }

    private fun goToNextResource(animated: Boolean, completion: () -> Unit): Boolean {
        val adapter = resourcePager.adapter ?: return false
        if (resourcePager.currentItem >= adapter.count - 1) {
            return false
        }

        resourcePager.setCurrentItem(resourcePager.currentItem + 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (publication.metadata.effectiveReadingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(webView.numPages - 1, false)
            } else {
                webView.setCurrentItem(0, false)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch { completion() }
        return true
    }

    private fun goToPreviousResource(animated: Boolean, completion: () -> Unit): Boolean {
        if (resourcePager.currentItem <= 0) {
            return false
        }

        resourcePager.setCurrentItem(resourcePager.currentItem - 1, animated)

        currentReflowablePageFragment?.webView?.let { webView ->
            if (publication.metadata.effectiveReadingProgression == ReadingProgression.RTL) {
                webView.setCurrentItem(0, false)
            } else {
                webView.setCurrentItem(webView.numPages - 1, false)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch { completion() }
        return true
    }

    private val r2PagerAdapter: R2PagerAdapter?
        get() = if (::resourcePager.isInitialized) resourcePager.adapter as? R2PagerAdapter
            else null

    private val currentReflowablePageFragment: R2EpubPageFragment? get() =
        currentFragment as? R2EpubPageFragment

    private val currentFragment: Fragment? get() =
        fragmentAt(resourcePager.currentItem)

    private fun fragmentAt(index: Int): Fragment? =
        r2PagerAdapter?.mFragments?.get(adapter.getItemId(index))

    /**
     * Returns the reflowable page fragment matching the given href, if it is already loaded in the
     * view pager.
     */
    private fun loadedFragmentForHref(href: String): R2EpubPageFragment? {
        val adapter = r2PagerAdapter ?: return null
        adapter.mFragments.forEach { _, fragment ->
            val pageFragment = fragment as? R2EpubPageFragment ?: return@forEach
            val link = pageFragment.link ?: return@forEach
            if (link.href == href) {
                return pageFragment
            }
        }
        return null
    }

    override val readingProgression: ReadingProgression
        get() = publication.metadata.effectiveReadingProgression

    override val currentLocator: StateFlow<Locator> get() = _currentLocator
    private val _currentLocator = MutableStateFlow(initialLocator
        ?: requireNotNull(publication.locatorFromLink(publication.readingOrder.first()))
    )

    /**
     * Returns the [Locator] to the first HTML element that begins on the current screen.
     */
    @ExperimentalReadiumApi
    override suspend fun firstVisibleElementLocator(): Locator? {
        if (!::resourcePager.isInitialized) return null

        val resource = publication.readingOrder[resourcePager.currentItem]
        return currentReflowablePageFragment?.webView?.findFirstVisibleLocator()
            ?.copy(
                href = resource.href,
                type = resource.type ?: MediaType.XHTML.toString()
            )
    }

    /**
     * While scrolling we receive a lot of new current locations, so we use a coroutine job to
     * debounce the notification.
     */
    private var debounceLocationNotificationJob: Job? = null

    /**
     * Mapping between reading order hrefs and the table of contents title.
     */
    private val tableOfContentsTitleByHref: Map<String, String> by lazy {
        fun fulfill(linkList: List<Link>): MutableMap<String, String> {
            var result: MutableMap<String, String> = mutableMapOf()

            for (link in linkList) {
                val title = link.title?: ""

                if (title.isNotEmpty()) {
                    result[link.href] = title
                }

                val subResult = fulfill(link.children)

                result = (subResult + result) as MutableMap<String, String>
            }

            return result
        }

        fulfill(publication.tableOfContents).toMap()
    }

    private fun notifyCurrentLocation() {
        val navigator = this
        debounceLocationNotificationJob?.cancel()
        debounceLocationNotificationJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(100L)

            if (currentReflowablePageFragment?.isLoaded?.value == false) {
                return@launch
            }

            val reflowableWebView = currentReflowablePageFragment?.webView
            val progression = reflowableWebView?.run {
                // The transition has stabilized, so we can ask the web view to refresh its current
                // item to reflect the current scroll position.
                updateCurrentItem()
                progression.coerceIn(0.0, 1.0)
            } ?: 0.0

            val resource = publication.readingOrder[resourcePager.currentItem]
            val positionLocator = publication.positionsByResource[resource.href]?.let { positions ->
                val index = ceil(progression * (positions.size - 1)).toInt()
                positions.getOrNull(index)
            }

            val currentLocator = Locator(
                href = resource.href,
                type = resource.type ?: MediaType.XHTML.toString(),
                title = tableOfContentsTitleByHref[resource.href] ?: positionLocator?.title ?: resource.title,
                locations = (positionLocator?.locations ?: Locator.Locations()).copy(
                    progression = progression
                ),
                text = positionLocator?.text ?: Locator.Text()
            )

            _currentLocator.value = currentLocator

            // Deprecated notifications
            navigatorDelegate?.locationDidChange(navigator = navigator, locator = currentLocator)
            reflowableWebView?.let {
                paginationListener?.onPageChanged(
                    pageIndex = it.mCurItem,
                    totalPages = it.numPages,
                    locator = currentLocator
                )
            }
        }
    }

    companion object {

        /**
         * Creates a factory for [EpubNavigatorFragment].
         *
         * @param publication EPUB publication to render in the navigator.
         * @param baseUrl A base URL where this publication is served from.
         * @param initialLocator The first location which should be visible when rendering the
         *        publication. Can be used to restore the last reading location.
         * @param listener Optional listener to implement to observe events, such as user taps.
         * @param config Additional configuration.
         */
        fun createFactory(
            publication: Publication,
            baseUrl: String,
            initialLocator: Locator? = null,
            listener: Listener? = null,
            paginationListener: PaginationListener? = null,
            config: Configuration = Configuration(),
        ): FragmentFactory =
            createFragmentFactory { EpubNavigatorFragment(publication, baseUrl, initialLocator, listener, paginationListener, config) }

    }

}
