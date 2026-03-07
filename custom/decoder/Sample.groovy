/**
 *
 *  Sample Custom decoder,
 *  decoder object is located at 'decoder' variable
 */

//
// Creates a Configuration Map
//
def optionsMap = [:]
optionsMap.token = 'nishimura';

//
// Apply the configuration map
//
decoder.setOptions(optionsMap)

//
// Creates the initClosure
//
def initClosure = {self->    
    println("Init Decoder Called: Options Map Size:["+self.options.size()+ "]");
    self.setDecoderRecreateInterval(20);
}

decoder.setInitClosure(initClosure);

def decodeToken = {context->
    context.userPrincipal.id = "Lucas Nishimura";
    println("Decoder Token is Called");
//    context.raiseException("Failed");
}


decoder.setDecodeTokenClosure(decodeToken);



