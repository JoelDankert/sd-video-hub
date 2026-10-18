#!/usr/bin/env node
'use strict';

const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');

const source=fs.readFileSync(path.join(__dirname,'index.html'),'utf8');
const html=source.replace('__SDH_CUTLIST__',JSON.stringify('cutlist.json'));
const file=(id,flight,extra={})=>({id,name:`stale-${id}.avi`,output:`/srv/archive/library/import-${id}/DCIM/100HDZRO/real-${id}.mp4`,status:'done',duration:10,flight_duration:flight,output_size:id*1000,video_url:`/media/${id}.mp4`,thumb_url:'',skip_intervals:[],...extra});
const files=[file(1,0),file(2,49.999),file(3,50),file(4,51),file(5,null,{duration:1}),file(6,undefined,{duration:2}),file(7,-1),file(8,'invalid'),file(22,60,{output:'/srv/archive/library/20261015-100409-sd-video/DCIM/100HDZRO/hdz_0003.mp4',video_url:'/media/id22.mp4'}),file(110,70,{output:'/srv/archive/library/20261018-183647-sd-video-0403-0201/DCIM/100HDZRO/hdz_0003.mp4',video_url:'/media/id110.mp4'})];
const state={files,sessions:[],devices:[],settings:{archive:{sort_direction:'asc'},playback:{skip_nonflight:true,crop_4_3:false}}};

async function serve(page){
  await page.route('**/*',route=>{
    const pathname=new URL(route.request().url()).pathname;
    if(pathname==='/')return route.fulfill({contentType:'text/html',body:html});
    if(pathname==='/api/state')return route.fulfill({json:state});
    return route.abort();
  });
}

(async()=>{
  const browser=await chromium.launch({headless:true});
  try{
    const context=await browser.newContext();
    let page=await context.newPage();page.setDefaultTimeout(5000);await serve(page);await page.goto('http://sdh.test/');await page.waitForSelector('.video');
    const toggle=page.locator('#hide-short-toggle');
    assert.equal(await toggle.isChecked(),false,'filter defaults off');
    assert.equal(await page.locator('.video').count(),files.length,'off shows every completed video');
    await toggle.check();
    assert.equal(await page.locator('.video').count(),files.length-2,'only recognized flight durations from 0 through under 50 are hidden');
    assert.match(await page.locator('#count').textContent(),/8 Videos/);assert.match(await page.locator('#count').textContent(),/165 KB/);
    assert.ok(await page.getByText('real-5.mp4',{exact:true}).isVisible(),'unknown flight duration remains visible despite short total duration');
    await page.evaluate(()=>{window.__galleryNode=document.querySelector('.video')});await page.waitForTimeout(1800);
    assert.ok(await page.evaluate(()=>document.querySelector('#hide-short-toggle').checked),'polling does not reset the toggle');
    await page.reload();await page.waitForSelector('.video');assert.ok(await toggle.isChecked(),'preference survives reload');
    await toggle.uncheck();assert.equal(await page.locator('.video').count(),files.length,'off restores hidden cards');

    const names=await page.locator('.name').allTextContents();
    assert.ok(names.includes('real-1.mp4'),'playable output basename is shown instead of stale input name');
    assert.ok(names.includes('hdz_0003.mp4 · 20261015-100409-sd-video'));
    assert.ok(names.includes('hdz_0003.mp4 · 20261018-183647-sd-video-0403-0201'));
    assert.equal(await page.locator('.name').filter({hasText:'hdz_0003.mp4'}).first().getAttribute('title'),'20261015-100409-sd-video/DCIM/100HDZRO/hdz_0003.mp4');
    const duplicateButtons=page.locator('button[data-url^="/media/id"]');
    assert.deepEqual(await duplicateButtons.evaluateAll(nodes=>nodes.map(n=>[n.dataset.url,n.dataset.name])),[['/media/id22.mp4','hdz_0003.mp4 · 20261015-100409-sd-video'],['/media/id110.mp4','hdz_0003.mp4 · 20261018-183647-sd-video-0403-0201']]);
    assert.deepEqual(await page.locator('a[href^="/media/id"]').evaluateAll(nodes=>nodes.map(n=>n.getAttribute('href'))),['/media/id22.mp4?download','/media/id110.mp4?download']);

    await page.locator('button[data-url="/media/1.mp4"]').click();assert.equal(await page.locator('#viewer').getAttribute('hidden'),null);
    await page.evaluate(()=>{hideShortToggle.checked=true;hideShortToggle.dispatchEvent(new Event('change',{bubbles:true}))});assert.equal(await page.locator('button[data-url="/media/1.mp4"]').count(),0);assert.equal(await page.locator('#viewer').getAttribute('hidden'),null,'filtering active video keeps viewer open');
    await page.evaluate(()=>{state.files.forEach(f=>f.flight_duration=0);render()});assert.match(await page.locator('.empty').textContent(),/Filter/);assert.doesNotMatch(await page.locator('.empty').textContent(),/keine komprimierten Videos/);
    await context.close();

    const broken=await browser.newContext();page=await broken.newPage();page.setDefaultTimeout(5000);
    await page.addInitScript(()=>Object.defineProperty(window,'localStorage',{configurable:true,get(){throw Error('unavailable')}}));
    await serve(page);await page.goto('http://broken.test/');await page.waitForSelector('.video');await page.locator('#hide-short-toggle').check();assert.equal(await page.locator('.video').count(),files.length-2,'unavailable storage does not break filtering');
    await broken.close();
    console.log('GALLERY_FILTER_REGRESSION_OK');
  }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1});
