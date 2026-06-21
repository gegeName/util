package com.chat.myapplication

import com.chat.uifoundation.http.HttpResult

class BaseResponse<T>(override val message: String?, override val result: T?, val code: Int) :
    HttpResult<T> {
    override fun success(): Boolean {
        return code == 200
    }

    override val errorCode: String
        get() = code.toString()
}
