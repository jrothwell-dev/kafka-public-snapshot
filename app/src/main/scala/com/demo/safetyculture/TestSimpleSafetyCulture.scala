package com.demo.safetyculture

import com.demo.config.ConfigLoader

object TestSimpleSafetyCulture {
  
  def main(args: Array[String]): Unit = {
    println("=" * 60)
    println("SafetyCulture WWCC Credential Test")
    println("=" * 60)
    
    val config = ConfigLoader.load()
    val service = new SafetyCultureService(config.safetyculture)
    
    val myEmail = "jordanr@murrumbidgee.nsw.gov.au"
    
    try {
      // Step 1: Get credential types
      println("Step 1: Finding WWCC credential type...")
      val wwccTypeId = service.listCredentialTypes() match {
        case Right(types) =>
          println(s"✓ Found ${types.size} credential types:")
          types.foreach(t => println(s"  - ${t.name} (${t.id})"))
          println()
          
          // Find WWCC type
          types.find(_.name.toLowerCase.contains("wwcc")) match {
            case Some(wwccType) =>
              println(s"✓ Found WWCC type: ${wwccType.name}")
              println(s"  ID: ${wwccType.id}")
              Some(wwccType.id)
            case None =>
              println("✗ WWCC credential type not found")
              None
          }
          
        case Left(err) =>
          println(s"✗ Failed to get credential types: $err")
          None
      }
      
      println()
      
      // Step 2: Find user
      println("Step 2: Finding user...")
      service.searchUserByEmail(myEmail) match {
        case Right(Some(user)) =>
          println(s"✓ Found: ${user.firstname} ${user.lastname}")
          println()
          
          // Step 3: Get WWCC credentials only
          println("Step 3: Fetching WWCC credentials...")
          service.getUserCredentials(user.id, wwccTypeId) match {
            case Right(credentials) =>
              if (credentials.isEmpty) {
                println("  No WWCC credentials found")
              } else {
                println(s"✓ Found ${credentials.size} WWCC credential(s):")
                println()
                credentials.foreach { c =>
                  println(s"  Document ID: ${c.document_id}")
                  c.attributes.credential_number.foreach(n => println(s"  WWCC Number: $n"))
                  c.attributes.expiry_period_start.foreach(s => println(s"  Valid From: $s"))
                  c.attributes.expiry_period_end.foreach(e => println(s"  Expires: $e"))
                  c.attributes.media.foreach { mediaList =>
                    println(s"  Attachments:")
                    mediaList.foreach(m => println(s"    - ${m.filename}"))
                  }
                  println()
                }
              }
              
            case Left(err) =>
              println(s"✗ Failed: $err")
          }
          
        case Right(None) =>
          println(s"✗ User not found")
          
        case Left(err) =>
          println(s"✗ Search failed: $err")
      }
      
    } finally {
      service.close()
    }
    
    println("=" * 60)
  }
}