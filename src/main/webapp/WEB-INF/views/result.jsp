<%--
  Created by IntelliJ IDEA.
  User: gnee
  Date: 15.06.2020
  Time: 9:43
  To change this template use File | Settings | File Templates.
--%>
<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<html>
<head>
    <title>Результат</title>
</head>
<body>

<h1>Расшифрованный текст:</h1>
<h2>>${text}</h2>

<p>Оригинал:</p>
<iframe src="${pageContext.request.contextPath}/resources/static/${file.getOriginalFilename()}" height="800" width="800"></iframe>
</body>
</html>
