#!/usr/bin/env node
'use strict';

const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');

const source=fs.readFileSync(path.join(__dirname,'index.html'),'utf8');
const html=source.replace('__SDH_CUTLIST__',JSON.stringify('cutlist.json'));
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const baseFile={id:1,name:'hdz_test.mp4',output:'/library/hdz_test.mp4',status:'done',duration:125,flight_duration:65,output_size:2000000,video_url:'/media/test.mp4',thumb_url:'/media/test.jpg',recorded_source:'embedded',recorded_at:1704196800};
const makeState=file=>({files:[file],sessions:[],devices:[],settings:{archive:{group_order:['hdzero'],sort_direction:'desc'},playback:{skip_nonflight:true,crop_4_3:false}}});

new Function(source.match(/<script>([\s\S]*)<\/script>/)[1].replace('__SDH_CUTLIST__',JSON.stringify('cutlist.json')));

async function serve(page,{state,delayState=0,onCutlist}){
  let active=0,maxActive=0,stateRequests=0,cutlistRequests=0;
  await page.route('**/*',async route=>{
    const pathname=new URL(route.request().url()).pathname;
    if(pathname==='/')return route.fulfill({contentType:'text/html',body:html});
    if(pathname==='/api/state'){
      stateRequests++;active++;maxActive=Math.max(maxActive,active);
      await wait(delayState);active--;
      return route.fulfill({contentType:'application/json',body:JSON.stringify(state)});
    }
    if(pathname==='/media/cutlist.json'){
      cutlistRequests++;
      return onCutlist(route,cutlistRequests);
    }
    return route.abort();
  });
  return {metrics:()=>({maxActive,stateRequests,cutlistRequests})};
}

(async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const modernContext=await browser.newContext({viewport:{width:412,height:915}}),modern=await modernContext.newPage();
    const modernServer=await serve(modern,{state:makeState({...baseFile,skip_intervals:[[0,4]]}),onCutlist:route=>route.fulfill({json:{videos:{}}})});
    const started=Date.now();await modern.goto('http://sdh.test/');await modern.waitForSelector('.video .thumb');
    const firstGalleryMs=Date.now()-started,ratios=[];
    for(const checked of [false,true,false]){await modern.locator('#crop-toggle').setChecked(checked);ratios.push(await modern.locator('.thumb').evaluate(node=>{const box=node.getBoundingClientRect();return box.width/box.height}))}
    assert.ok(firstGalleryMs<1200,'modern gallery must render without optional metadata');
    assert.equal(modernServer.metrics().cutlistRequests,0,'modern records must not request the legacy cutlist');
    assert.ok(ratios.every(ratio=>Math.abs(ratio-4/3)<.02),'thumbnail ratio must remain 4:3');
    await modernContext.close();

    const pollingContext=await browser.newContext(),polling=await pollingContext.newPage();
    const pollingServer=await serve(polling,{state:makeState({...baseFile,skip_intervals:[]}),delayState:2000,onCutlist:route=>route.fulfill({json:{videos:{}}})});
    await polling.goto('http://sdh.test/');await wait(4200);
    assert.equal(pollingServer.metrics().maxActive,1,'state polling must be single-flight');
    await polling.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>true});Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'hidden'});document.dispatchEvent(new Event('visibilitychange'))});
    const hiddenStart=pollingServer.metrics().stateRequests;await wait(1800);
    assert.equal(pollingServer.metrics().stateRequests,hiddenStart,'hidden pages must not poll');
    await polling.evaluate(()=>{Object.defineProperty(document,'hidden',{configurable:true,get:()=>false});Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'visible'});document.dispatchEvent(new Event('visibilitychange'))});
    await wait(100);assert.equal(pollingServer.metrics().stateRequests,hiddenStart+1,'visibility resume must refresh once');
    await pollingContext.close();

    const stalledContext=await browser.newContext(),stalled=await stalledContext.newPage();
    const stalledServer=await serve(stalled,{state:makeState({...baseFile}),onCutlist:async route=>{await wait(3000);if(!route.request().isNavigationRequest())await route.fulfill({json:{videos:{}}}).catch(()=>{})}});
    const legacyStarted=Date.now();await stalled.goto('http://sdh.test/');await stalled.waitForSelector('.video');
    assert.ok(Date.now()-legacyStarted<1200,'legacy gallery must render before a stalled cutlist');
    await wait(2200);assert.equal(stalledServer.metrics().cutlistRequests,1,'stalled cutlist requests must stay bounded');
    await stalledContext.close();

    const legacyContext=await browser.newContext(),legacy=await legacyContext.newPage();
    await serve(legacy,{state:makeState({...baseFile}),onCutlist:async route=>{await wait(250);await route.fulfill({json:{videos:{'hdz_test.mp4':[[2,7]]}}})}});
    await legacy.goto('http://sdh.test/');await legacy.waitForSelector('.video');
    await legacy.locator('.actions>button').click();
    await legacy.evaluate(()=>{window.__galleryNode=document.querySelector('.video');window.__playerNode=document.querySelector('#viewer-video')});
    await legacy.waitForFunction(()=>JSON.parse(decodeURIComponent(document.querySelector('.actions>button').dataset.skips)).length===1);
    assert.ok(await legacy.evaluate(()=>!viewer.hidden&&viewer===document.querySelector('#viewer')&&window.__playerNode===document.querySelector('#viewer-video')),'late metadata must preserve the active viewer');
    await legacy.evaluate(()=>{window.__galleryNode=document.querySelector('.video')});await wait(1800);
    assert.ok(await legacy.evaluate(()=>window.__galleryNode===document.querySelector('.video')),'unchanged polling must preserve gallery nodes');
    await legacyContext.close();

    console.log(JSON.stringify({first_gallery_ms:firstGalleryMs,thumbnail_ratios:ratios,max_concurrent_state_requests:pollingServer.metrics().maxActive,legacy_stalled_cutlist_requests:stalledServer.metrics().cutlistRequests}));
    console.log('FRONTEND_PERFORMANCE_REGRESSION_OK');
  }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1});
