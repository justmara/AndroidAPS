package app.aaps.core.validators.validators

class TimeValidator(customErrorMessage: String?) : RegexpValidator(customErrorMessage, "^\\d{1,2}:\\d{1,2}$")