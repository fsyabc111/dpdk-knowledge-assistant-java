"""纯validator用人工已知事件模型；不是实际JVM/HTTP/业务hook采样。CPU窗口内仅写文件。"""
import json
from pathlib import Path


def synthetic_text():
    spec=json.loads(Path(__file__).resolve().parents[1].joinpath('process-spec.json').read_text())
    dimensions={
        'assistant_llm_requests_total':{'engine':'llama_index','result':'success'},
        'assistant_llm_request_duration_seconds':{'engine':'llama_index'},
        'assistant_chat_stage_duration_seconds':{'stage':'rewrite'},
        'assistant_rate_limit_hits_total':{'kind':'per_minute'},
        'assistant_rag_outcomes_total':{'status':'answered','reason':'supported'},
        'assistant_rag_validation_duration_seconds':{'result':'answered'},
        'assistant_agent_tool_calls_total':{'tool':'grade_plugin','ok':'true'},
        'assistant_agent_tool_duration_seconds':{'tool':'grade_plugin'},
        'assistant_agent_tool_result_bytes':{'tool':'grade_plugin'},
        'assistant_upstream_usage_tokens_total':{'stage':'answer','kind':'llm','source':'upstream'},
        'assistant_upstream_usage_cost_micros_total':{'stage':'answer','kind':'llm','model':'synthetic','currency':'USD'},
        'assistant_upstream_usage_unknown_total':{'stage':'answer','kind':'llm'},
        'http_requests_total':{'method':'GET','status':'2xx','handler':'/api/health'},
        'http_request_size_bytes':{'handler':'/api/health'},'http_response_size_bytes':{'handler':'/api/health'},
        'http_request_duration_seconds':{'method':'GET','handler':'/api/health'},
    }
    # 每项独立人工字面量；validator不能据此证明正式业务hook已执行。
    values={'assistant_llm_requests_total':1,'assistant_llm_request_duration_seconds':.2,
        'assistant_chat_retrieval_duration_seconds':.05,'assistant_chat_stage_duration_seconds':.01,
        'assistant_chat_fallbacks_total':1,'assistant_rate_limit_hits_total':1,'assistant_rag_outcomes_total':1,
        'assistant_rag_duration_seconds':2,'assistant_rag_validation_duration_seconds':1,'assistant_agent_tool_calls_total':1,
        'assistant_agent_tool_duration_seconds':.2,'assistant_agent_tool_result_bytes':1024,'assistant_agent_request_tokens':5000,
        'assistant_upstream_usage_tokens_total':21,'assistant_upstream_usage_cost_micros_total':7,'assistant_upstream_usage_unknown_total':1,
        'http_requests_total':1,'http_request_size_bytes':0,'http_response_size_bytes':11,
        'http_request_duration_highr_seconds':.02,'http_request_duration_seconds':.02}
    lines=[]
    def sample(name,tags,value):
        suffix='{'+','.join(key+'="'+str(value)+'"' for key,value in tags.items())+'}' if tags else ''
        lines.append(name+suffix+' '+str(float(value)))
    # 这些值只属于明确合成fixture，正式实现必须读取真实MXBean/SystemProperty。
    for row in spec['jvm']:
        name=row['name'];lines.append('# TYPE '+name+' '+row['type'])
        if name=='jvm_gc_collections_total':sample(name,{'collector':'synthetic'},2)
        elif name=='jvm_gc_collection_seconds_total':sample(name,{'collector':'synthetic'},.05)
        elif name=='jvm_memory_used_bytes':sample(name,{'area':'heap'},134217728);sample(name,{'area':'nonheap'},33554432)
        else:sample(name,{'version':'fixture','vendor':'synthetic','runtime':'synthetic'},1)
    for row in spec['domain']+spec['http']:
        name=row['name'];kind=row['type'];tags=dimensions.get(name,{})
        lines.append('# TYPE '+name+' '+kind)
        if kind=='counter':sample(name,tags,values[name])
        else:
            if kind=='histogram':
                for bound in row['buckets']:sample(name+'_bucket',{**tags,'le':str(float(bound))},1 if values[name]<=bound else 0)
                sample(name+'_bucket',{**tags,'le':'+Inf'},1)
            sample(name+'_count',tags,1);sample(name+'_sum',tags,values[name])
        created=name[:-6]+'_created' if kind=='counter' else name+'_created'
        lines.append('# TYPE '+created+' gauge');sample(created,tags,1000)
    return '\n'.join(lines)+'\n'
