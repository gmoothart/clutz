declare namespace ಠ_ಠ.clutz.googmodule {
  class Required extends Required_Instance {
  }
  class Required_Instance {
    private noStructuralTyping_: any;
  }
}
declare namespace ಠ_ಠ.clutz.goog {
  function require(name: 'googmodule.Required'): typeof ಠ_ಠ.clutz.googmodule.Required;
}
declare module 'goog:googmodule.Required' {
  import alias = ಠ_ಠ.clutz.googmodule.Required;
  export default alias;
}
declare namespace ಠ_ಠ.clutz.googmodule.TheModule {
  var a : number ;
  var b : number ;
  var required : ಠ_ಠ.clutz.googmodule.Required ;
}
declare namespace ಠ_ಠ.clutz.goog {
  function require(name: 'googmodule.TheModule'): typeof ಠ_ಠ.clutz.googmodule.TheModule;
}
declare module 'goog:googmodule.TheModule' {
  import alias = ಠ_ಠ.clutz.googmodule.TheModule;
  export = alias;
}
declare namespace ಠ_ಠ.clutz.googmodule.requiredModule {
  var rm : number ;
}
declare namespace ಠ_ಠ.clutz.goog {
  function require(name: 'googmodule.requiredModule'): typeof ಠ_ಠ.clutz.googmodule.requiredModule;
}
declare module 'goog:googmodule.requiredModule' {
  import alias = ಠ_ಠ.clutz.googmodule.requiredModule;
  export = alias;
}