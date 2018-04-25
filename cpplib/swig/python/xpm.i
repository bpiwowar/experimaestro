// Python slots
// See https://docs.python.org/3/c-api/typeobj.html

%include "collection.i"


// Pythonic renames and mappings

%feature("python:slot", "tp_str",functype = "reprfunc") *::toString;
%feature("python:slot", "tp_repr", functype = "reprfunc") *::toString;
%feature("python:slot", "tp_call", functype = "ternarycallfunc") *::call;
%feature("python:slot", "tp_hash", functype = "hashfunc") *::hash;
%feature("python:slot", "tp_getattro", functype = "binaryfunc") *::__getattro__;

%rename(append) *::push_back;


// Attributes
%attribute(xpm::Argument, bool, required, required, required);
%ignore xpm::Argument::required;

// Attributes
%attribute(xpm::Argument, bool, ignore, ignore, ignore);
%ignore xpm::Argument::ignore;

/*%attributeval(xpm::Argument, std::shared_ptr<xpm::Object>, Object, defaultValue, defaultValue)
%ignore xpm::Argument::defaultValue;
*/

/*%feature("naturalvar", 0) std::shared_ptr<xpm::Generator>;
attributeval(xpm::Argument, xpm::Generator, generator, generator, generator)
%ignore xpm::Argument::generator;
*/

%attribute(xpm::Argument, std::string, help, help, help)
%ignore xpm::Argument::help;

/*%attribute(xpm::Argument, Type, type, type, type)*/
/*%ignore xpm::Argument::type;*/

%extend xpm::Value { %COLLECTION(std::shared_ptr<xpm::Configuration>) };





// TODO: GARBAGE SECTION....

#ifndef SWIG

/** 
 * Returns the wrapped python object rather than the director object.
 * This is useful since an XPM object might be subclassed
*/


%{
    #include <xpm/common.hpp>

      // FIXME: REMOVE
   #include <cxxabi.h>
   template<typename T>
    std::string demangle(T * t) {
      int status;
    char * demangled = abi::__cxa_demangle(typeid(*t).name(),0,0,&status);
    std::string r = demangled;
    free(demangled);
    return r;
}


   namespace xpm { namespace python {

      PyObject * getRealObject(std::shared_ptr<xpm::Object> const &object) {
         if (object) {
            // This is a Director object
            if (Swig::Director * d = SWIG_DIRECTOR_CAST(object.get())) {
               Py_INCREF(d->swig_get_self());
               return d->swig_get_self();
            }
   
            // Check for internal types
            swig_type_info *typeinfo;

            if (std::shared_ptr<  xpm::Array > p = std::dynamic_pointer_cast<xpm::Array>(object)) {
               std::shared_ptr<  xpm::Array > * smartresult = new std::shared_ptr<xpm::Array>(p);
               return SWIG_InternalNewPointerObj(SWIG_as_voidptr(smartresult), SWIGTYPE_p_std__shared_ptrT_xpm__Array_t, SWIG_POINTER_OWN);
            }
            
            if (std::shared_ptr<  xpm::Value > p = std::dynamic_pointer_cast<xpm::Value>(object)) {
               std::shared_ptr<  xpm::Value > * smartresult = new std::shared_ptr<xpm::Value>(p);
               return SWIG_InternalNewPointerObj(SWIG_as_voidptr(smartresult), SWIGTYPE_p_std__shared_ptrT_xpm__Value_t, SWIG_POINTER_OWN);
            }

            std::shared_ptr< xpm::Object > * smartresult = new std::shared_ptr<xpm::Object>(object);
            return SWIG_InternalNewPointerObj(SWIG_as_voidptr(smartresult), SWIGTYPE_p_std__shared_ptrT_xpm__Object_t, SWIG_POINTER_OWN);
         }


         // Returns None
         return SWIG_Py_Void();
      }
      
   }} // Ends xpm::python
%}




%typemap(out) std::shared_ptr<xpm::Object> {
    // OUT-OBJECT
    $result = xpm::python::getRealObject($1);
}

%typemap(directorin) std::shared_ptr<xpm::Object> const & {
    $input = xpm::python::getRealObject($1);
}




%extend xpm::Object {
    /*void __setitem__(std::string const & key, std::shared_ptr<xpm::Object> const &value) {
        (*($self))[key] = value;
    }
    void __setitem__(std::string const & key, std::map<std::string, std::shared_ptr<xpm::Object>> &value) {
        (*($self))[key] = std::make_shared<xpm::Object>(value);
    }
    void __setitem__(std::string const & key, Value const &value) {
        (*($self))[key] = std::make_shared<xpm::Object>(value);
    }*/

    PyObject * __getattro__(PyObject *name) {
        auto _self = xpm::python::getRealObject($self->shared_from_this());

        PyObject *object = PyObject_GenericGetAttr(_self, name);
        if (object) {
            return object;
        }

        if (!PyUnicode_Check(name)) {
            PyErr_SetString(PyExc_AttributeError, "Attribute name is not a string");
            return nullptr;
        }

        Py_ssize_t stringsize;
        char *_key = (char*)PyUnicode_AsUTF8AndSize(name, &stringsize);
        std::string key(_key, stringsize);

      if ($self->hasKey(key)) {
          PyErr_Clear();
         return xpm::python::getRealObject($self->get(key));
      }

      // std::cerr << "Could not find attribute " << key << "\n";
      PyErr_SetString(PyExc_AttributeError, (std::string("Could not find attribute ") + key).c_str());
      return nullptr;
    }

    PyObject *call() {
        // If we have a value, just return it
        if (auto valuePtr = dynamic_cast<xpm::Value*>($self)) {
            switch(valuePtr->scalarType()) {
                case xpm::ValueType::BOOLEAN:
                    if (valuePtr->asBoolean()) { Py_RETURN_TRUE; } else { Py_RETURN_FALSE; }

                case xpm::ValueType::NONE:
                    return SWIG_Py_Void();

                case xpm::ValueType::PATH: {
                    auto pathPtr = new xpm::Path($self->asPath());
                    return SWIG_InternalNewPointerObj(pathPtr, $descriptor(xpm::Path*), SWIG_POINTER_OWN );
                }

                case xpm::ValueType::STRING:
                    return PyUnicode_FromString($self->asString().c_str());

                case xpm::ValueType::INTEGER:
                    return PyLong_FromLong($self->asInteger());

                case xpm::ValueType::REAL:
                    return PyFloat_FromDouble($self->asReal());
            }
        }

        // Otherwise return void
        return SWIG_Py_Void();
    }
}

#endif