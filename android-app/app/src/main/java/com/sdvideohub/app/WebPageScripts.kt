package com.sdvideohub.app

/** Also exercised by the real-browser playback harness while a video is running. */
object WebPageScripts {
    const val REFRESH = """(()=>{if(typeof load!=='function')return false;Promise.resolve(load()).catch(()=>{});return true})()"""
}
