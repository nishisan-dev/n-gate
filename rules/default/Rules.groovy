/**
 * Hello xD
 *
 */
 
/**
 * This processor check if the response can be streamed
 */
def binaryDataProcessor = {workload-> 
    println('Processing...');
    def contentSize = workload.upstreamResponse.getHeader("Content-Length");
    if (contentSize){  
        contentSize = contentSize.toLong();
        if (contentSize > 100000){           
            workload.clientResponse.addHeader('x-big', 'yes');
            workload.returnPipe = true;
        }else{
            workload.clientResponse.addHeader('x-big', 'no');
        }        
    }
    def contentType = workload.upstreamResponse.getHeader("Content-Type");
    if (contentType.contains("image")){
        workload.returnPipe = true;            
        workload.clientResponse.addHeader('x-content-type', contentType);
    }else{
        workload.clientResponse.addHeader('x-content-type', 'none');
    }    
    

}
/**
 * Response processors são custosos, use com sabedoria
 */
//workload.addResponseProcessor('binaryDataProcessor',binaryDataProcessor)

//workload.returnPipe = true;
//def m = [:];
//m.name = 'Lucas';
//m.age  = 40;
//upstreamRequest.setRequestURI("/");
//request.setQueryString("auth=true&teste=1");
//request.setBackend("api-2");
//println('Hello:' + request.getRequestURI())
//context.json(m);




//println("Hello:" + workload.context.userPrincipal.id)



//def nishiBe = utils.httpClient.getAssyncBackend("nishisan-dev");
//
//
//def req =  nishiBe.get("https://nishisan.dev",[:]);
//def a = req.join();

//workload.objects.put("aResponse",a.body().string());


//def synth = workload.createSynthResponse();
//synth.setContent("Hello");

//
//synth.setStatus(404);
//synth.addHeader('teste','teste');

//synth.getWriter().write("{\"message\": \"ok\"}");
//synth.setJson();
//synth.setContent(utils.gson.toJson(workload.objects.get("USER_PRINCIPAL")));
//synth.getWriter().flush();
//synth.getWriter().close();

//def bodyProcessor = {workload ->
////    print(workload.objects.aResponse)
//      workload.clientResponse.body(workload.objects.aResponse);
//      print(workload.clientResponse);
//}


//workload.addResponseProcessor('processor2',bodyProcessor)
//print(a.body().string())
//workload.response.
//print(workload.context.pathParam("userId"));