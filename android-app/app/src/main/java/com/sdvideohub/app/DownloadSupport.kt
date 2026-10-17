package com.sdvideohub.app

import java.net.URLDecoder
import java.net.URI
import java.nio.charset.StandardCharsets

enum class DownloadKind { MEDIA, FLIGHT_EXPORT }

data class NativeDownload(val path: String, val kind: DownloadKind, val suggestedName: String)

object DownloadRouting {
    private val exportToken = Regex("[A-Za-z0-9_-]{12,160}")

    fun fromLoopbackUrl(url: String, trustedOrigin: String): NativeDownload? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val origin = "${uri.scheme}://${uri.rawAuthority}"
        if (origin != trustedOrigin || uri.rawFragment != null || uri.userInfo != null) return null
        val path = uri.rawPath ?: return null
        if (path.startsWith("/media/") && uri.rawQuery == "download") {
            val encoded = path.removePrefix("/media/")
            val mediaPath = decoded(encoded)
            if (encoded.isBlank() || mediaPath.any { it.isISOControl() } || mediaPath.split('/').any { it == ".." }) return null
            val filename = mediaPath.substringAfterLast('/').substringAfterLast('\\')
            return NativeDownload("$path?download", DownloadKind.MEDIA, safeFilename(filename, "video.mp4"))
        }
        if (path.startsWith("/flight-download/") && uri.rawQuery == null) {
            val token = path.removePrefix("/flight-download/")
            if (!exportToken.matches(token)) return null
            return NativeDownload(path, DownloadKind.FLIGHT_EXPORT, "flight-video.mp4")
        }
        return null
    }

    fun safeFilename(value: String?, fallback: String): String {
        val cleaned = value.orEmpty().replace(Regex("[\\p{Cntrl}/\\\\]"), "_").trim().trim('.')
        return cleaned.take(180).ifBlank { fallback }
    }

    fun contentDispositionFilename(header: String?, fallback: String): String {
        if (header.isNullOrBlank()) return safeFilename(fallback, "video.mp4")
        val encoded = Regex("(?i)(?:^|;)\\s*filename\\*=UTF-8''([^;]+)").find(header)?.groupValues?.get(1)
        val plain = Regex("(?i)(?:^|;)\\s*filename=\"([^\"]*)\"").find(header)?.groupValues?.get(1)
            ?: Regex("(?i)(?:^|;)\\s*filename=([^;]+)").find(header)?.groupValues?.get(1)?.trim()
        val candidate = encoded?.let(::decoded) ?: plain
        return safeFilename(candidate, fallback)
    }

    private fun decoded(value: String) = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}

object SettingsBootstrap {
    const val CROP = "sd-video-hub.crop-4-3.v1"
    const val SKIP = "sd-video-hub.skip-nonflight.v1"
    val keys = setOf(CROP, SKIP)

    fun script(nativeValuesJson: String): String = """
        (()=>{if(location.origin!==__SDH_ORIGIN__)return;
        const readSaved=()=>typeof SDHNative.loadSettings==='function'?JSON.parse(SDHNative.loadSettings(location.origin)):$nativeValuesJson;
        const saved=readSaved();
        for(const key of [${js(CROP)},${js(SKIP)}]){try{const value=saved[key],old=localStorage.getItem(key);if(value==='true'||value==='false')localStorage.setItem(key,value);else if(old==='true'||old==='false')SDHNative.saveSetting(location.origin,key,old);}catch(_){}}
        document.addEventListener('DOMContentLoaded',()=>{const latest=readSaved();for(const [id,key] of [['crop-toggle',${js(CROP)}],['skip-toggle',${js(SKIP)}]]){const value=latest[key],x=document.getElementById(id);if(x&&(value==='true'||value==='false')){x.checked=value==='true';x.dispatchEvent(new Event('change',{bubbles:true}));}}});
        document.addEventListener('change',e=>{const x=e.target;if(x&&x.type==='checkbox'&&(x.id==='crop-toggle'||x.id==='skip-toggle'))SDHNative.saveSetting(location.origin,x.id==='crop-toggle'?${js(CROP)}:${js(SKIP)},String(x.checked));},true);
        document.addEventListener('click',e=>{const a=e.target&&e.target.closest?e.target.closest('a[download]'):null;if(!a)return;if(SDHNative.captureDownload(location.origin,a.href)){e.preventDefault();e.stopImmediatePropagation();}},true);
        })();
    """.trimIndent()

    private fun js(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
