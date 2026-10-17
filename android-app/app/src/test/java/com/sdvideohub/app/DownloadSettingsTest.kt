package com.sdvideohub.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSettingsTest {
    @Test fun nativePreferencesOverrideStaleOriginStorageAndRestoreAtNewOrigin() {
        runBootstrap(false, true, "18787")
        runBootstrap(false, false, "18788")
    }

    @Test fun unavailableLocalStorageDoesNotBreakSettingsOrDownloadCapture() {
        runBootstrap(true, false, "18787")
    }

    private fun runBootstrap(brokenStorage: Boolean, stale: Boolean, port: String) {
        val script = SettingsBootstrap.script("{\"${SettingsBootstrap.CROP}\":\"false\",\"${SettingsBootstrap.SKIP}\":\"true\"}")
            .replace("__SDH_ORIGIN__", "\"http://127.0.0.1:$port\"")
        val harness = """
            const vm=require('node:vm'), assert=require('node:assert/strict');
            const crop='sd-video-hub.crop-4-3.v1', skip='sd-video-hub.skip-nonflight.v1';
            const broken=process.argv[2]==='true', stale=process.argv[3]==='true';
            const origin='http://127.0.0.1:'+process.argv[4], values=stale?{[crop]:'true',[skip]:'false'}:{};
            const native={},listeners={}, boxes={}; let captured=0,prevented=0;
            for(const id of ['crop-toggle','skip-toggle']) boxes[id]={id,type:'checkbox',checked:false,dispatchEvent(e){listeners.change({target:this});}};
            const context={location:{origin},Event:class {constructor(type){this.type=type;}},
              localStorage:{getItem(k){if(broken)throw Error('unavailable');return values[k]??null;},setItem(k,v){if(broken)throw Error('unavailable');values[k]=String(v);}},
              document:{addEventListener(k,fn){listeners[k]=fn;},getElementById(id){return boxes[id];}},
              SDHNative:{saveSetting(o,k,v){assert.equal(o,origin);native[k]=v;},captureDownload(o,url){assert.equal(o,origin);assert.ok(url.includes('/flight-download/'));captured++;return true;}}};
            vm.runInNewContext(process.argv[1],context);
            if(!broken){assert.equal(values[crop],'false');assert.equal(values[skip],'true');}
            listeners.DOMContentLoaded();
            assert.equal(boxes['crop-toggle'].checked,false);assert.equal(boxes['skip-toggle'].checked,true);
            assert.equal(native[crop],'false');assert.equal(native[skip],'true');
            boxes['crop-toggle'].checked=true;listeners.change({target:boxes['crop-toggle']});assert.equal(native[crop],'true');
            listeners.click({target:{closest(){return {href:origin+'/flight-download/Abc_123456789'};}},preventDefault(){prevented++;},stopImmediatePropagation(){}});
            assert.equal(captured,1);assert.equal(prevented,1);
            context.SDHNative.loadSettings=()=>JSON.stringify(native);
            vm.runInNewContext(process.argv[1],context);listeners.DOMContentLoaded();
            assert.equal(boxes['crop-toggle'].checked,true);assert.equal(boxes['skip-toggle'].checked,true);
            console.log('bootstrap ok');
        """.trimIndent()
        val process = ProcessBuilder("node", "-e", harness, script, brokenStorage.toString(), stale.toString(), port).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }
}
