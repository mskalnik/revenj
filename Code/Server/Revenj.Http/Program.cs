﻿using System;
using System.Configuration;
using DSL;

namespace Revenj.Http
{
	static class Program
	{
		static void Main(string[] args)
		{
			foreach (var arg in args)
			{
				var i = arg.IndexOf('=');
				if (i != -1)
				{
					var name = arg.Substring(0, i);
					var value = arg.Substring(i + 1);
					ConfigurationManager.AppSettings[name] = value;
				}
			}
			var httpServer = ConfigurationManager.AppSettings["Revenj.HttpServer"];
			Console.WriteLine("Starting server");
			if (httpServer == "Socket")
			{
				var server = Platform.Start<HttpSocketServer>();
				server.Run();
			}
			else
			{
				var server = Platform.Start<HttpListenerServer>();
				server.Run();
			}
		}
	}
}
