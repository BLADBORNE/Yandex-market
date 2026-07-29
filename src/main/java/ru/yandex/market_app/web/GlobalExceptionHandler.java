package ru.yandex.market_app.web;

import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import ru.yandex.market_app.dto.ErrorResponse;
import ru.yandex.market_app.exception.ItemNotFoundException;
import ru.yandex.market_app.exception.NotRemoveItemException;
import ru.yandex.market_app.exception.OperationNotSupportedException;
import ru.yandex.market_app.util.TemplateAttributeNameUtil;
import ru.yandex.market_app.util.TemplateNameUtil;

import java.util.NoSuchElementException;

@ControllerAdvice
public final class GlobalExceptionHandler {

    @ExceptionHandler({ItemNotFoundException.class, NoSuchElementException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public String handleNotFoundException(RuntimeException exception, Model model) {
        return buildResponse(exception, HttpStatus.NOT_FOUND, model);
    }

    @ExceptionHandler({NotRemoveItemException.class, OperationNotSupportedException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public String handleBadRequestException(RuntimeException exception, Model model) {
        return buildResponse(exception, HttpStatus.BAD_REQUEST, model);
    }

    private String buildResponse(RuntimeException exception, HttpStatus status, Model model) {
        var response = new ErrorResponse(status.value(), status.getReasonPhrase(), exception.getMessage());
        model.addAttribute(TemplateAttributeNameUtil.ERROR_RESPONSE, response);

        return TemplateNameUtil.ERROR;
    }
}
