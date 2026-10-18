import type {FeaturePlugin,UiEffect,UiResult,UiNode,Json} from '../../../sdk';

/**
 * AI 答疑（DeepSeek 单端点）。
 *
 * 移植自安卓项目 app/src/main/java/com/hnnujw/course/review/ 的 AI 链路：
 *   - 端点与模型取自 AiEndpoint.kt：只认 https://api.deepseek.com，模型
 *     deepseek-flash / deepseek-v4-pro，不允许手填端点；
 *   - 提示词与历史上限取自 AiChatClient.kt：SYSTEM_PROMPT 原样保留，
 *     HISTORY_LIMIT = 8（一道题的追问 8 轮足够，再长只会白烧 token）；
 *   - 思考过程（reasoning_content）与用量（usage）照安卓端一样带回来展示。
 *
 * 与安卓端不同的地方，都是平台约束导致的，不是随手改的：
 *   1. 悬浮球做不了：平台 54 个宿主能力里没有悬浮窗 / overlay，原生组件也没有
 *      拖拽与绝对定位，所以"问 AI"改成页面上的一颗按钮（点击 = 悬浮球点击）。
 *   2. API Key 走 contributes.settings 由宿主保管，**不写进插件存储**
 *      （SDK 明确要求存储里不得放凭据）；未配置时一个请求都不发。
 *   3. 不流式：network.request 是一次性返回整包，所以 stream 置 false，
 *      没有安卓端的 SSE 增量上屏。
 */
const BASE_URL='https://api.deepseek.com';
const DEFAULT_MODEL='deepseek-flash';
const MODELS=['deepseek-flash','deepseek-v4-pro'];
const HISTORY_LIMIT=8;
const MAX_TOKENS=1200;
const SYSTEM_PROMPT='你是一位耐心的学习助手，正在为学生讲解一道练习题。'+
 '请用简体中文回答，条理清晰、直接给出结论，不要寒暄。'+
 '格式要求：先给一句话结论，再用简短的分点展开；篇幅控制在 300 字以内。';

type Sample={id:string;type:'single'|'multi'|'custom';stem:string;options:string[];answer:string;chapter:string;explanation:string};
/** 通用示例题（非任何学校的真实试题），用于演示"点一下就讲解"。 */
const SAMPLES:Sample[]=[
 {id:'s1',type:'single',stem:'在计算机中，1 字节（Byte）等于多少位（bit）？',options:['4 位','8 位','16 位','32 位'],answer:'B',chapter:'第一章 计算机基础',explanation:'1 Byte = 8 bit，这是存储容量的基本换算关系。'},
 {id:'s2',type:'single',stem:'下列存储器中，断电后数据会丢失的是（ ）。',options:['ROM','RAM','硬盘','U 盘'],answer:'B',chapter:'第一章 计算机基础',explanation:'RAM 是易失性存储器，断电后内容立即丢失。'},
 {id:'s3',type:'multi',stem:'下列设备中，属于输出设备的有（ ）。',options:['显示器','打印机','键盘','音箱'],answer:'ABD',chapter:'第二章 计算机系统',explanation:'显示器、打印机、音箱都是把计算机处理结果送出的设备；键盘是输入设备。'},
 {id:'s4',type:'single',stem:'十进制数 13 转换为二进制是（ ）。',options:['1101','1011','1110','1001'],answer:'A',chapter:'第一章 计算机基础',explanation:'13 = 8 + 4 + 1，对应二进制 1101。'},
 {id:'s5',type:'custom',stem:'',options:[],answer:'',chapter:'自定义',explanation:''}
];

type Turn={role:'user'|'assistant';text:string};
type State={
 serial:number;pending:string;status:string;
 index:number;stem:string;options:string[];answer:string;chapter:string;my:string;
 reply:string;reasoning:string;usage:string;history:Turn[];showReasoning:boolean;
 askText:string;model:string;modelsList:string;configured:boolean;
 /** 本次请求实际发出去的提问原文。回答回来时靠它把"问了什么"记进历史 ——
  *  effect.result 事件里已经拿不到当初那句追问了。 */
 lastUser:string;
};

function sampleOf(state:State):Sample{return SAMPLES[state.index]??SAMPLES[0];}

/** 拼给模型的提问文本，与安卓端 AiChatClient.buildQuestionPrompt 同构。 */
function buildPrompt(state:State,extra:string):string{
 const s=sampleOf(state),lines:string[]=[];
 lines.push('题目类型：'+(s.type==='multi'?'多选题':'单选题'));
 if(state.chapter)lines.push('所属章节：'+state.chapter);
 lines.push('题干：'+(state.stem||'(未填写)'));
 if(state.options.length){
  lines.push('选项：');
  state.options.forEach((opt,i)=>lines.push(String.fromCharCode(65+i)+'. '+opt));
 }
 if(state.answer)lines.push('正确答案：'+state.answer);
 if(state.my){
  const right=state.my.toUpperCase().split('').sort().join('')===state.answer.toUpperCase().split('').sort().join('');
  lines.push('我的作答：'+state.my+(right?'（回答正确）':'（回答错误）'));
  if(!right)lines.push('请重点分析：我为什么会选错，以及正确选项为什么对。');
 }
 if(s.explanation&&s.type!=='custom')lines.push('（题库自带解析，仅供你参考，不要照抄）'+s.explanation);
 if(extra)lines.push(extra);
 return lines.join('\n');
}

function messagesFor(state:State,extra:string):Json[]{
 const out:Json[]=[{role:'system',content:SYSTEM_PROMPT}];
 for(const turn of state.history.slice(-HISTORY_LIMIT))out.push({role:turn.role,content:turn.text});
 out.push({role:'user',content:buildPrompt(state,extra)});
 return out;
}

function view(state:State,effects:UiEffect[]=[]):UiResult<State>{
 const busy=!!state.pending,children:UiNode[]=[
  {id:'title',type:'text',style:'title',text:'AI 答疑'},
  {id:'status',type:'text',style:'caption',text:state.status},
  {id:'pick',type:'select',label:'示例题',value:SAMPLES[state.index]?.id??'s1',event:'pick',
   options:SAMPLES.map(s=>({value:s.id,label:s.id==='s5'?'自定义题目':(s.chapter+' · '+s.stem.slice(0,16)+(s.stem.length>16?'…':''))}))},
  {id:'stem',type:'input',label:'题干',value:state.stem,event:'stem',inputType:'multiline',placeholder:'可以直接粘贴自己的题目',enabled:!busy}
 ];
 state.options.forEach((opt,i)=>children.push({id:'opt.'+i,type:'text',style:'body',text:String.fromCharCode(65+i)+'. '+opt}));
 children.push({id:'my',type:'input',label:'我的作答（可留空）',value:state.my,event:'my',placeholder:'例如 A，多选题写 ABD',enabled:!busy});
 children.push({id:'ask',type:'button',label:busy?'正在生成…':'问 AI',event:'ask',enabled:!busy});
 if(state.showReasoning&&state.reasoning)children.push({id:'reasoning',type:'text',style:'caption',text:'思考过程：'+state.reasoning});
 if(state.reply)children.push({id:'reply',type:'text',style:'body',text:state.reply});
 if(state.usage)children.push({id:'usage',type:'text',style:'caption',text:state.usage});
 if(state.history.length){
  children.push({id:'askText',type:'input',label:'继续追问',value:state.askText,event:'askText',enabled:!busy},
   {id:'follow',type:'button',label:'发送追问',event:'follow',enabled:!busy&&!!state.askText.trim()},
   {id:'clear',type:'button',label:'清空对话',event:'clear',enabled:!busy});
 }
 children.push({id:'toggleReasoning',type:'toggle',label:'显示思考过程',value:state.showReasoning,event:'toggleReasoning'},
  {id:'models',type:'button',label:'获取可用模型',event:'models',enabled:!busy});
 if(state.modelsList)children.push({id:'modelsList',type:'text',style:'caption',text:state.modelsList});
 return {state,view:{id:'root',type:'scroll',padding:16,gap:12,children},effects};
}

function initial(index:number,model:string,configured:boolean):State{
 const s=SAMPLES[index]??SAMPLES[0];
 return {serial:0,pending:'',status:configured?'已配置 API Key，点「问 AI」即可讲解。':'还没有配置 DeepSeek API Key：请到本插件的设置里填写，插件不会保存你的 Key。',
  index,stem:s.stem,options:s.options.slice(),answer:s.answer,chapter:s.chapter,my:'',
  reply:'',reasoning:'',usage:'',history:[],showReasoning:false,askText:'',
  model:model||DEFAULT_MODEL,modelsList:'',configured,lastUser:''};
}

const plugin:FeaturePlugin<State>={ui:{
 init:({pageId},context)=>{
  const key=String(context.settings?.apiKey||'').trim();
  const model=String(context.settings?.model||DEFAULT_MODEL);
  return view(initial(0,MODELS.includes(model)?model:DEFAULT_MODEL,!!key));
 },
 reduce:({state,event},context)=>{
  const next={...state},effects:UiEffect[]=[];
  const send=(capability:string,version:number,input:Record<string,Json>,tag:string)=>{
   next.pending=tag+'.'+(++next.serial);
   effects.push({id:next.pending,capability,version,input});
  };
  const key=String(context.settings?.apiKey||'').trim();
  const ask=(extra:string,tag:string)=>{
   if(!key){next.status='还没有配置 DeepSeek API Key：请到本插件的设置里填写后再问。';return;}
   next.lastUser=buildPrompt(state,extra);
   const body={model:state.model,messages:messagesFor(state,extra),stream:false,max_tokens:MAX_TOKENS,temperature:0.2};
   send('network.request',3,{
    url:BASE_URL+'/chat/completions',method:'POST',purpose:'query',
    headers:{authorization:'Bearer '+key,'content-type':'application/json'},
    body:JSON.stringify(body)
   },tag);
   next.status='已发送请求，等待 DeepSeek 返回…';
  };
  if(event.type==='effect.result'){
   if(event.effectId!==next.pending)return view(next);
   const tag=(event.effectId||'').split('.')[0];
   next.pending='';
   if(!event.result?.ok){next.status='请求失败：'+(event.result?.error.message||'未知原因');}
   else{
    const data=event.result.data as {status?:number;body?:string};
    if(tag==='models'){
     try{
      const parsed=JSON.parse(String(data.body||'{}')) as {data?:{id?:string}[]};
      const ids=(parsed.data||[]).map(m=>String(m.id||'')).filter(Boolean);
      next.modelsList=ids.length?('可用模型：'+ids.join('、')):'服务端没有返回模型列表。';
     }catch{next.modelsList='模型列表解析失败。';}
    }else{
     try{
      const parsed=JSON.parse(String(data.body||'{}')) as {
       choices?:{message?:{content?:string;reasoning_content?:string}}[];
       usage?:{prompt_tokens?:number;completion_tokens?:number;total_tokens?:number}};
      const message=parsed.choices?.[0]?.message;
      const text=String(message?.content||'').trim();
      if(!text)throw new Error('empty');
      next.reply=text;
      next.reasoning=String(message?.reasoning_content||'').trim();
      const usage=parsed.usage;
      next.usage=usage?('本次用量：提示 '+usage.prompt_tokens+' / 完成 '+usage.completion_tokens+' / 合计 '+usage.total_tokens+' tokens'):'本次用量：服务端未返回';
      // 追问历史：把这一轮的"问"和"答"都记下来，最多 8 轮。
      const asked:Turn[]=[{role:'user',text:next.lastUser||buildPrompt(state,'')},{role:'assistant',text}];
      next.history=[...state.history,...asked].slice(-HISTORY_LIMIT*2);
      next.askText='';
      next.status='已生成讲解。';
     }catch{
      next.status='DeepSeek 返回的内容无法解析（HTTP '+(data.status??'?')+'）：'+String(data.body||'').slice(0,160);
     }
    }
   }
  }else if(next.pending)return view(next);
  else if(event.name==='pick'){
   const idx=SAMPLES.findIndex(s=>s.id===String(event.value||''));
   const keep={...next};
   const fresh=initial(idx<0?0:idx,state.model,state.configured);
   // 换题必须清空历史与用量：上一题的问答对下一题是噪声，还白烧 token。
   return view({...fresh,serial:keep.serial,showReasoning:keep.showReasoning,modelsList:keep.modelsList});
  }
  else if(event.name==='stem')next.stem=String(event.value||'').slice(0,4000);
  else if(event.name==='my')next.my=String(event.value||'').slice(0,64);
  else if(event.name==='askText')next.askText=String(event.value||'').slice(0,1000);
  else if(event.name==='toggleReasoning')next.showReasoning=!state.showReasoning;
  else if(event.name==='clear'){const fresh=initial(state.index,state.model,state.configured);return view({...fresh,serial:next.serial,modelsList:state.modelsList});}
  else if(event.name==='ask')ask('','ask');
  else if(event.name==='follow'){if(!state.askText.trim())next.status='先写一句追问。';else ask(state.askText.trim(),'follow');}
  else if(event.name==='models'){
   if(!key){next.status='还没有配置 DeepSeek API Key。';}
   else send('network.request',3,{url:BASE_URL+'/models',method:'GET',purpose:'query',headers:{authorization:'Bearer '+key}},'models');
  }
  return view(next,effects);
 }
}};
export default plugin;
